package com.cobre.accounts.support;

import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.ReadEvent;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.DecisionType;
import com.cobre.accounts.domain.model.EntryId;
import com.cobre.accounts.domain.model.EntryLine;
import com.cobre.accounts.domain.model.Money;
import com.cobre.accounts.domain.model.RejectionCode;
import com.cobre.accounts.domain.service.PostingResult;
import com.cobre.accounts.domain.service.PostingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sequential reference implementation of a load (spec §12.1, F07 oracle): one thread, in memory, no
 * database and no scheduler. The concurrent load must give exactly the same decisions and balances.
 *
 * <p>It reuses what has its own tests and is not what AC11 and AC12 check: {@code EventFileReader}
 * (rules 1 and 2, T03) produces the {@link EventFile}s, and {@link PostingService} (rules 3 to 6,
 * T02) decides each event. It implements on its own what the concurrent load could get wrong: the
 * D4 order, the D6 duplicates within a load and across loads with {@code payload_mismatch}, and the
 * balances (CLIENT accounts carried forward, Cobre accounts as the sum of their lines, D10). D7 is
 * off. It does not build the JSON report; T07 tests that.
 */
public final class SequentialOracle {

    /** What happened to one event read. */
    public enum Kind { APPLIED, REJECTED, DUPLICATE }

    /**
     * One event read, with the fields the report shows for it (§8.1).
     *
     * @param code and {@code detail}: only for REJECTED
     * @param decisionStored only for REJECTED; false for an event without identity (§7.2)
     * @param originalDecision and {@code payloadMismatch}: only for DUPLICATE
     */
    public record Outcome(String eventId, String file, int position, Kind kind, RejectionCode code, String detail,
            boolean decisionStored, DecisionType originalDecision, boolean payloadMismatch) {}

    /** The decision kept for an {@code event_id} (D6), as {@code settlement_decision} stores it. */
    public record Decision(DecisionType decision, RejectionCode code, String detail, String payloadHash) {}

    /** D4 (§7.3): operation_date (absent = oldest), event_id (absent = ""), file name, position, payload_hash. */
    private static final Comparator<ReadEvent> D4 = Comparator
            .comparing((ReadEvent e) -> e.operationDate() == null ? Instant.MIN : e.operationDate())
            .thenComparing(e -> e.eventId() == null ? "" : e.eventId())
            .thenComparing(ReadEvent::fileName)
            .thenComparingInt(ReadEvent::position)
            .thenComparing(ReadEvent::payloadHash, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<AccountId, Account> clients = new HashMap<>();
    private final Map<AccountId, BigDecimal> cobreBalances = new HashMap<>();
    private final Map<AccountId, CurrencyCode> currencies = new HashMap<>();
    private final Map<String, Decision> decisions = new HashMap<>();
    private final AtomicLong entryIds = new AtomicLong();
    private final PostingService posting =
            new PostingService(() -> new EntryId(new UUID(0, entryIds.incrementAndGet())), false);

    private SequentialOracle() {}

    /**
     * @param seedJson the seed in the format of §6.3; opening balances are lines against
     *     {@code COBRE_FUNDING_<currency>} (D9)
     */
    public static SequentialOracle fromSeed(byte[] seedJson, Set<CurrencyCode> supported) {
        SequentialOracle oracle = new SequentialOracle();
        for (CurrencyCode currency : supported) {
            oracle.openCobre(AccountId.fxPosition(currency), currency);
            oracle.openCobre(AccountId.funding(currency), currency);
        }
        for (JsonNode node : JSON.readTree(seedJson).get("accounts")) {
            CurrencyCode currency = CurrencyCode.of(node.get("currency").asString());
            AccountId id = AccountId.of(node.get("account_id").asString());
            Money initial = Money.of(node.get("initial_balance").asString(), currency);
            oracle.clients.put(id, Account.client(id, currency,
                    AccountStatus.valueOf(node.get("status").asString()), initial));
            oracle.currencies.put(id, currency);
            oracle.cobreBalances.merge(AccountId.funding(currency), initial.amount().negate(), BigDecimal::add);
        }
        return oracle;
    }

    private void openCobre(AccountId id, CurrencyCode currency) {
        cobreBalances.put(id, BigDecimal.ZERO.setScale(2));
        currencies.put(id, currency);
    }

    /** Applies one load and returns every event read, in D4 order, with its outcome. */
    public List<Outcome> load(List<EventFile> files) {
        List<ReadEvent> events = new ArrayList<>();
        files.forEach(file -> events.addAll(file.events()));
        events.sort(D4);
        List<Outcome> outcomes = new ArrayList<>(events.size());
        for (ReadEvent event : events) {
            outcomes.add(decide(event));
        }
        return outcomes;
    }

    private Outcome decide(ReadEvent event) {
        if (!event.hasIdentity()) {
            // §7.2: reported by file and position, never a duplicate, no decision stored.
            ReadEvent.Invalid invalid = (ReadEvent.Invalid) event.outcome();
            return rejected(event, invalid.code(), invalid.detail(), false);
        }
        Decision original = decisions.get(event.eventId());
        if (original != null) {
            // D6 and §7.5: the same rule within a load and across loads.
            return new Outcome(event.eventId(), event.fileName(), event.position(), Kind.DUPLICATE, null, null,
                    false, original.decision(), !original.payloadHash().equals(event.payloadHash()));
        }
        return switch (event.outcome()) {
            case ReadEvent.Invalid invalid -> {
                decisions.put(event.eventId(),
                        new Decision(DecisionType.REJECTED, invalid.code(), invalid.detail(), event.payloadHash()));
                yield rejected(event, invalid.code(), invalid.detail(), true);
            }
            case ReadEvent.Translated translated -> switch (posting.post(translated.command(), Map.copyOf(clients))) {
                case PostingResult.Posted posted -> {
                    posted.updatedAccounts().forEach(account -> clients.put(account.id(), account));
                    for (EntryLine line : posted.entry().lines()) {
                        if (!clients.containsKey(line.accountId())) {
                            cobreBalances.merge(line.accountId(), line.signedAmount().amount(), BigDecimal::add);
                        }
                    }
                    decisions.put(event.eventId(),
                            new Decision(DecisionType.APPLIED, null, null, event.payloadHash()));
                    yield new Outcome(event.eventId(), event.fileName(), event.position(), Kind.APPLIED, null, null,
                            false, null, false);
                }
                case PostingResult.Rejected rejected -> {
                    decisions.put(event.eventId(),
                            new Decision(DecisionType.REJECTED, rejected.code(), rejected.detail(), event.payloadHash()));
                    yield rejected(event, rejected.code(), rejected.detail(), true);
                }
            };
        };
    }

    private static Outcome rejected(ReadEvent event, RejectionCode code, String detail, boolean stored) {
        return new Outcome(event.eventId(), event.fileName(), event.position(), Kind.REJECTED, code, detail, stored,
                null, false);
    }

    /** Balance of every account, scale 2: CLIENT carried forward, Cobre the sum of its lines (D10). */
    public Map<AccountId, BigDecimal> balances() {
        Map<AccountId, BigDecimal> balances = new HashMap<>(cobreBalances);
        clients.forEach((id, account) -> balances.put(id, account.balance().orElseThrow().amount()));
        return Collections.unmodifiableMap(balances);
    }

    /** Per currency, the sum of all balances; AC12 says it is always 0.00. */
    public SortedMap<CurrencyCode, BigDecimal> invariant() {
        SortedMap<CurrencyCode, BigDecimal> invariant = new TreeMap<>();
        balances().forEach((id, balance) -> invariant.merge(
                Objects.requireNonNull(currencies.get(id), id.value()), balance, BigDecimal::add));
        return invariant;
    }

    /** Every decision taken so far, by {@code event_id}. */
    public Map<String, Decision> decisions() {
        return Collections.unmodifiableMap(decisions);
    }

    // --- Views to compare with a report (§8.1) ------------------------------------------------

    /** The report's {@code rejected} entries, one line each with the same fields, in D4 order. */
    public static List<String> rejectedView(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> o.kind() == Kind.REJECTED)
                .map(o -> String.join(" ", String.valueOf(o.eventId()), o.file(), String.valueOf(o.position()),
                        o.code().name(), String.valueOf(o.detail()), String.valueOf(o.decisionStored())))
                .toList();
    }

    public static List<String> rejectedView(JsonNode report) {
        List<String> lines = new ArrayList<>();
        for (JsonNode r : report.get("rejected")) {
            lines.add(String.join(" ", text(r.get("event_id")), r.get("file").asString(),
                    String.valueOf(r.get("position").asInt()), r.get("code").asString(), text(r.get("detail")),
                    String.valueOf(r.get("decision_stored").asBoolean())));
        }
        return lines;
    }

    /** The report's {@code duplicates} entries, one line each with the same fields, in D4 order. */
    public static List<String> duplicatesView(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> o.kind() == Kind.DUPLICATE)
                .map(o -> String.join(" ", o.eventId(), o.file(), String.valueOf(o.position()),
                        o.originalDecision().name(), String.valueOf(o.payloadMismatch())))
                .toList();
    }

    public static List<String> duplicatesView(JsonNode report) {
        List<String> lines = new ArrayList<>();
        for (JsonNode d : report.get("duplicates")) {
            lines.add(String.join(" ", d.get("event_id").asString(), d.get("file").asString(),
                    String.valueOf(d.get("position").asInt()), d.get("original_decision").asString(),
                    String.valueOf(d.get("payload_mismatch").asBoolean())));
        }
        return lines;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asString();
    }
}
