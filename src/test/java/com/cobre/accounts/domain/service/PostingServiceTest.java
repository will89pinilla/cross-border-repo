package com.cobre.accounts.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.PostSettlement;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.service.PostingResult.Posted;
import com.cobre.accounts.domain.service.PostingResult.Rejected;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Rules 3 to 6 of spec §7.2, in order, and the entry of spec §7.1. */
class PostingServiceTest {

    private static final CurrencyCode MXN = CurrencyCode.of("MXN");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Instant AT = Instant.parse("2025-09-09T15:32:10Z");
    private static final EntryId ENTRY = new EntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String EVENT = "cbmm_20250909_000123";

    private static final AccountId ORIGIN_ID = AccountId.of("ACC123456789");
    private static final AccountId DESTINATION_ID = AccountId.of("ACC987654321");
    private static final AccountId UNKNOWN_ID = AccountId.of("ACC000000000");

    private final PostingService service = new PostingService(() -> ENTRY, false);
    private final PostingService autoCreate = new PostingService(() -> ENTRY, true);

    private static Account mxn(AccountId id, String balance, AccountStatus status) {
        return Account.client(id, MXN, status, Money.of(balance, MXN));
    }

    private static Account usd(AccountId id, String balance, AccountStatus status) {
        return Account.client(id, USD, status, Money.of(balance, USD));
    }

    private static Map<AccountId, Account> accounts(Account... accounts) {
        return Arrays.stream(accounts).collect(Collectors.toMap(Account::id, Function.identity()));
    }

    /** Case example (spec §2): origin pays MXN, destination receives USD. */
    private static PostSettlement settlement(AccountId origin, String originAmount, AccountId destination, String destinationAmount) {
        return new PostSettlement(EVENT, AT, origin, Money.of(originAmount, MXN), destination, Money.of(destinationAmount, USD));
    }

    private static PostSettlement caseExample() {
        return settlement(ORIGIN_ID, "15000.50", DESTINATION_ID, "880.25");
    }

    private static Map<AccountId, Account> caseAccounts() {
        return accounts(mxn(ORIGIN_ID, "200000.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE));
    }

    private static Rejected rejected(PostingResult result) {
        assertThat(result).isInstanceOf(Rejected.class);
        return (Rejected) result;
    }

    private static Posted posted(PostingResult result) {
        assertThat(result).isInstanceOf(Posted.class);
        return (Posted) result;
    }

    // --- Applied ---

    @Test
    void applied_buildsFourLinesOfSection7_1() {
        Posted posted = posted(service.post(caseExample(), caseAccounts()));

        assertThat(posted.entry().id()).isEqualTo(ENTRY);
        assertThat(posted.entry().sourceEventId()).isEqualTo(EVENT);
        assertThat(posted.entry().postedAt()).isEqualTo(AT);
        assertThat(posted.entry().lines())
                .extracting(EntryLine::accountId, EntryLine::direction, EntryLine::amount, EntryLine::postedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ORIGIN_ID, Direction.DEBIT, Money.of("15000.50", MXN), AT),
                        org.assertj.core.groups.Tuple.tuple(AccountId.of("COBRE_FX_POSITION_MXN"), Direction.CREDIT, Money.of("15000.50", MXN), AT),
                        org.assertj.core.groups.Tuple.tuple(AccountId.of("COBRE_FX_POSITION_USD"), Direction.DEBIT, Money.of("880.25", USD), AT),
                        org.assertj.core.groups.Tuple.tuple(DESTINATION_ID, Direction.CREDIT, Money.of("880.25", USD), AT));
    }

    @Test
    void applied_updatesClientBalances_caseExample() {
        Posted posted = posted(service.post(caseExample(), caseAccounts()));

        // Spec §2: 200,000.00 -> 184,999.50 MXN and 0.00 -> 880.25 USD. Sorted by account_id.
        assertThat(posted.updatedAccounts()).containsExactly(
                mxn(ORIGIN_ID, "184999.50", AccountStatus.ACTIVE),
                usd(DESTINATION_ID, "880.25", AccountStatus.ACTIVE));
        assertThat(posted.createdAccounts()).isEmpty();
    }

    @Test
    void applied_neverTouchesCobreBalances_D10() {
        Posted posted = posted(service.post(caseExample(), caseAccounts()));
        assertThat(posted.updatedAccounts()).allMatch(Account::isClient);
    }

    @Test
    void applied_doesNotMutateInputAccounts() {
        Map<AccountId, Account> input = caseAccounts();
        service.post(caseExample(), input);
        assertThat(input.get(ORIGIN_ID).balance()).contains(Money.of("200000.00", MXN));
    }

    // --- Rule 3: ACCOUNT_NOT_FOUND ---

    @Test
    void rule3_originNotFound() {
        Rejected rejected = rejected(service.post(
                settlement(UNKNOWN_ID, "1.00", DESTINATION_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void rule3_destinationNotFound() {
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "1.00", UNKNOWN_ID, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    @Test
    void rule3_cobreAccountAsOrigin_isNotFound() {
        Map<AccountId, Account> withCobre = caseAccounts();
        withCobre = new java.util.HashMap<>(withCobre);
        withCobre.put(AccountId.fxPosition(MXN), Account.fxPosition(MXN, AccountStatus.ACTIVE));

        Rejected rejected = rejected(service.post(
                settlement(AccountId.fxPosition(MXN), "1.00", DESTINATION_ID, "1.00"), withCobre));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void rule3_cobreAccountAsDestination_isNotFound() {
        Map<AccountId, Account> withCobre = new java.util.HashMap<>(caseAccounts());
        withCobre.put(AccountId.funding(USD), Account.funding(USD, AccountStatus.ACTIVE));

        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "1.00", AccountId.funding(USD), "1.00"), withCobre));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    @Test
    void rule3_autoCreate_neverCreatesCobreAccount() {
        // D7 on, COBRE_ id not in the store: still ACCOUNT_NOT_FOUND, nothing created (spec §7.2).
        // The whole value is compared: a Rejected carries nothing besides code and detail.
        Rejected asDestination = rejected(autoCreate.post(
                settlement(ORIGIN_ID, "1.00", AccountId.of("COBRE_FX_POSITION_USD"), "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE))));
        assertThat(asDestination.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(asDestination.detail()).isEqualTo("DESTINATION");
        assertThat(asDestination).isEqualTo(new Rejected(RejectionCode.ACCOUNT_NOT_FOUND, "DESTINATION"));

        Rejected asOrigin = rejected(autoCreate.post(
                settlement(AccountId.of("COBRE_NEW_MXN"), "1.00", DESTINATION_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(asOrigin.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(asOrigin.detail()).isEqualTo("ORIGIN");
        assertThat(asOrigin).isEqualTo(new Rejected(RejectionCode.ACCOUNT_NOT_FOUND, "ORIGIN"));
    }

    @Test
    void rule3_autoCreate_createsUnknownDestinationInLegCurrency() {
        Posted posted = posted(autoCreate.post(
                settlement(ORIGIN_ID, "100.00", UNKNOWN_ID, "50.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE))));

        assertThat(posted.createdAccounts()).containsExactly(usd(UNKNOWN_ID, "0.00", AccountStatus.ACTIVE));
        assertThat(posted.updatedAccounts()).containsExactly(
                usd(UNKNOWN_ID, "50.00", AccountStatus.ACTIVE),
                mxn(ORIGIN_ID, "0.00", AccountStatus.ACTIVE));
    }

    @Test
    void rule3_autoCreate_unknownOrigin_isInsufficientFunds() {
        // The origin is opened at 0.00, so rule 6 rejects; being a rejection, it creates no account.
        Rejected rejected = rejected(autoCreate.post(
                settlement(UNKNOWN_ID, "1.00", DESTINATION_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.INSUFFICIENT_FUNDS);
        assertThat(rejected.detail()).isNull();
    }

    // --- Rule 4: CURRENCY_MISMATCH ---

    @Test
    void rule4_currencyMismatchOrigin() {
        // AC05 shape: ACC987654321 is USD and is used as an MXN origin.
        Rejected rejected = rejected(service.post(
                settlement(DESTINATION_ID, "1.00", ORIGIN_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "100.00", AccountStatus.ACTIVE), mxn(ORIGIN_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void rule4_currencyMismatchDestination() {
        AccountId otherMxn = AccountId.of("ACC555555555");
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "1.00", otherMxn, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), mxn(otherMxn, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    // --- Rule 5: ACCOUNT_INACTIVE ---

    @Test
    void rule5_inactiveOrigin_blocked() {
        Rejected rejected = rejected(service.post(caseExample(), accounts(
                mxn(ORIGIN_ID, "200000.00", AccountStatus.BLOCKED), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_INACTIVE);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void rule5_inactiveDestination_closed() {
        Rejected rejected = rejected(service.post(caseExample(), accounts(
                mxn(ORIGIN_ID, "200000.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.CLOSED))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_INACTIVE);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    // --- Rule 6: INSUFFICIENT_FUNDS ---

    @Test
    void rule6_insufficientFunds() {
        // AC02 shape: 100.00 MXN, debit of 150.00.
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "150.00", DESTINATION_ID, "8.80"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.INSUFFICIENT_FUNDS);
        assertThat(rejected.detail()).isNull();
    }

    @Test
    void rule6_isAValue_checkedBeforeDebit() {
        // Account.debit would throw NegativeClientBalanceException; the service must return a rejection instead.
        assertThatCode(() -> service.post(
                        settlement(ORIGIN_ID, "100.01", DESTINATION_ID, "1.00"),
                        accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))))
                .doesNotThrowAnyException();
    }

    @Test
    void rule6_exactBalance_isApplied() {
        Posted posted = posted(service.post(
                settlement(ORIGIN_ID, "100.00", DESTINATION_ID, "5.50"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(posted.updatedAccounts()).contains(mxn(ORIGIN_ID, "0.00", AccountStatus.ACTIVE));
    }

    // --- Precedence: the first failing rule wins ---

    @Test
    void precedence_destinationNotFound_beatsOriginCurrencyMismatch() {
        // Origin is USD but pays MXN (rule 4) and destination does not exist (rule 3): rule 3 runs
        // for both legs before rule 4 looks at any leg.
        Rejected rejected = rejected(service.post(
                settlement(DESTINATION_ID, "1.00", UNKNOWN_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "100.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    @Test
    void precedence_cobreAccountWithWrongCurrency_isNotFound() {
        // COBRE_FX_POSITION_USD used as an MXN origin: not addressable (rule 3), never CURRENCY_MISMATCH.
        Map<AccountId, Account> withCobre = new java.util.HashMap<>(caseAccounts());
        withCobre.put(AccountId.fxPosition(USD), Account.fxPosition(USD, AccountStatus.ACTIVE));
        Rejected rejected = rejected(service.post(
                settlement(AccountId.fxPosition(USD), "1.00", DESTINATION_ID, "1.00"), withCobre));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void precedence_destinationCurrencyMismatch_beatsOriginInactive() {
        AccountId otherMxn = AccountId.of("ACC555555555");
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "1.00", otherMxn, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.BLOCKED), mxn(otherMxn, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    @Test
    void precedence_inactiveOrigin_beatsInsufficientFunds() {
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "150.00", DESTINATION_ID, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.BLOCKED), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_INACTIVE);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void precedence_bothNotFound_isOrigin() {
        Rejected rejected = rejected(service.post(
                settlement(UNKNOWN_ID, "1.00", AccountId.of("ACC111111111"), "1.00"), accounts()));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_NOT_FOUND);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void precedence_bothCurrenciesWrong_isOrigin() {
        // Origin is USD but pays MXN; destination is MXN but receives USD.
        Rejected rejected = rejected(service.post(
                settlement(DESTINATION_ID, "1.00", ORIGIN_ID, "1.00"),
                accounts(usd(DESTINATION_ID, "100.00", AccountStatus.ACTIVE), mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(rejected.detail()).isEqualTo("ORIGIN");
    }

    @Test
    void precedence_inactiveDestination_beatsInsufficientFunds() {
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "150.00", DESTINATION_ID, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.BLOCKED))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.ACCOUNT_INACTIVE);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");
    }

    @Test
    void sameAccountBothLegs_isCurrencyMismatchDestination() {
        // Spec §7.2: the legs use different currencies (rule 2), so one account can only match the
        // origin; rule 4 rejects the destination.
        Rejected rejected = rejected(service.post(
                settlement(ORIGIN_ID, "1.00", ORIGIN_ID, "1.00"),
                accounts(mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE))));
        assertThat(rejected.code()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
        assertThat(rejected.detail()).isEqualTo("DESTINATION");

        // Same with D7 and an unknown id: opened in the origin's currency, then rule 4 on the destination.
        Rejected withAutoCreate = rejected(autoCreate.post(settlement(UNKNOWN_ID, "1.00", UNKNOWN_ID, "1.00"), accounts()));
        assertThat(withAutoCreate).isEqualTo(new Rejected(RejectionCode.CURRENCY_MISMATCH, "DESTINATION"));
    }

    // --- A rejection changes nothing ---

    @Test
    void rejected_changesNoBalance() {
        Map<AccountId, Account> input = accounts(
                mxn(ORIGIN_ID, "100.00", AccountStatus.ACTIVE), usd(DESTINATION_ID, "0.00", AccountStatus.ACTIVE));
        Rejected rejected = rejected(service.post(settlement(ORIGIN_ID, "150.00", DESTINATION_ID, "1.00"), input));

        // The inputs are untouched; a Rejected has nothing else to apply (rejected_carriesNoAccounts).
        assertThat(rejected.code()).isEqualTo(RejectionCode.INSUFFICIENT_FUNDS);
        assertThat(input.get(ORIGIN_ID).balance()).contains(Money.of("100.00", MXN));
        assertThat(input.get(DESTINATION_ID).balance()).contains(Money.zero(USD));
        assertThat(input.values()).allMatch(a -> a.type() == AccountType.CLIENT);
    }

    @Test
    void rejected_carriesNoAccounts() {
        // Spec §7.2: a rejection has no effects (no balance, no line, no D7 account). The type makes
        // it impossible to persist anything from a Rejected: it only has the code and the detail.
        assertThat(Rejected.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("code", "detail");
    }
}
