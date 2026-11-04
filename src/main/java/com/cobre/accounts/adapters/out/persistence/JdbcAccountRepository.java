package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.out.AccountRepository;
import com.cobre.accounts.domain.model.Account;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code account} table (spec §9). Only CLIENT rows store a balance; a Cobre balance is a SUM over
 * {@code entry_line}, done in SQL and turned into {@link Money} once (D10).
 */
public final class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS = "account_id, currency, type, status, balance";

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE account_id = ?")
                .params(id.value())
                .query(JdbcAccountRepository::toAccount)
                .optional();
    }

    /**
     * M2: the ids are sorted in Java (AccountId.compareTo, by code point) and the rows are locked by
     * {@code ORDER BY account_id COLLATE "C"}, the same order (UTF-8 bytes), so the lock order of
     * D12 does not depend on the database collation. LockRows sits above the sort in the plan, so
     * rows are locked in that order.
     */
    @Override
    public List<Account> lockClientAccounts(Collection<AccountId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String[] sorted = new TreeSet<>(ids).stream().map(AccountId::value).toArray(String[]::new);
        return jdbc.sql("SELECT " + COLUMNS + " FROM account"
                        + " WHERE account_id = ANY (?) AND type = 'CLIENT'"
                        + " ORDER BY account_id COLLATE \"C\" FOR UPDATE")
                .params((Object) sorted)
                .query(JdbcAccountRepository::toAccount)
                .list();
    }

    @Override
    public boolean insertIfAbsent(Account account) {
        BigDecimal balance = account.balance().map(Money::amount).orElse(null);
        int inserted = jdbc.sql("""
                        INSERT INTO account (account_id, currency, type, status, balance)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (account_id) DO NOTHING""")
                .params(account.id().value(), account.currency().value(), account.type().name(),
                        account.status().name(), balance)
                .update();
        return inserted == 1;
    }

    @Override
    public void updateBalance(Account account) {
        Money balance = account.balance().orElseThrow(() -> new IllegalArgumentException(
                "Only CLIENT accounts store a balance (D10): " + account.id()));
        int updated = jdbc.sql("UPDATE account SET balance = ? WHERE account_id = ? AND type = 'CLIENT'")
                .params(balance.amount(), account.id().value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("No CLIENT account to update: " + account.id());
        }
    }

    @Override
    public Optional<Money> balanceOf(AccountId id) {
        return jdbc.sql("""
                        SELECT a.currency,
                               CASE WHEN a.type = 'CLIENT' THEN a.balance
                                    ELSE (SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount
                                                                               ELSE -l.amount END), 0)
                                          FROM entry_line l WHERE l.account_id = a.account_id)
                               END AS balance
                        FROM account a WHERE a.account_id = ?""")
                .params(id.value())
                .query((rs, row) -> new Money(rs.getBigDecimal("balance"), CurrencyCode.of(rs.getString("currency"))))
                .optional();
    }

    private static Account toAccount(ResultSet rs, int row) throws SQLException {
        AccountId id = AccountId.of(rs.getString("account_id"));
        CurrencyCode currency = CurrencyCode.of(rs.getString("currency"));
        AccountStatus status = AccountStatus.valueOf(rs.getString("status"));
        Account account = switch (AccountType.valueOf(rs.getString("type"))) {
            case CLIENT -> Account.client(id, currency, status, new Money(rs.getBigDecimal("balance"), currency));
            case COBRE_POSITION -> Account.fxPosition(currency, status);
            case COBRE_FUNDING -> Account.funding(currency, status);
        };
        if (!account.id().equals(id)) {
            throw new IllegalStateException("Cobre account " + id + " does not follow the glossary id " + account.id());
        }
        return account;
    }
}
