package com.cobre.accounts.adapters.out.persistence;

import com.cobre.accounts.application.port.in.AccountBalance;
import com.cobre.accounts.application.port.in.EntryLinePage;
import com.cobre.accounts.application.port.out.AccountReadModel;
import com.cobre.accounts.domain.model.AccountId;
import com.cobre.accounts.domain.model.AccountStatus;
import com.cobre.accounts.domain.model.AccountType;
import com.cobre.accounts.domain.model.CurrencyCode;
import com.cobre.accounts.domain.model.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read model over {@code account} and {@code entry_line} (spec §8.1, §8.2, §9). A CLIENT balance is
 * the stored one; a Cobre balance is {@code SUM} of its lines in SQL, CREDIT positive and DEBIT
 * negative, turned into a number once (D10), with no cap of its own: it is read as a plain
 * {@link BigDecimal}, never as {@code Money}.
 *
 * <p>Lines are ordered by {@code posted_at}, then {@code source_event_id COLLATE "C"} (UTF-8 bytes,
 * the same order as {@link String#compareTo} for the ASCII ids of §6.1, so this order and the
 * report's, built in Java, agree), then {@code line_no}.
 */
public final class JdbcAccountReadModel implements AccountReadModel {

    /** Balance of every account selected by {@code %s}, CLIENT stored and Cobre summed. */
    private static final String BALANCES = """
            SELECT a.account_id, a.type, a.status, a.currency,
                   CASE WHEN a.type = 'CLIENT' THEN a.balance
                        ELSE (SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END), 0)
                              FROM entry_line l WHERE l.account_id = a.account_id)
                   END AS balance
            FROM account a WHERE %s""";

    private final JdbcClient jdbc;

    public JdbcAccountReadModel(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AccountBalance> balanceOf(AccountId id) {
        return jdbc.sql(BALANCES.formatted("a.account_id = ?"))
                .params(id.value())
                .query(JdbcAccountReadModel::toBalance)
                .optional();
    }

    @Override
    public Map<AccountId, BigDecimal> balancesOf(Collection<AccountId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String[] values = new TreeSet<>(ids).stream().map(AccountId::value).toArray(String[]::new);
        Map<AccountId, BigDecimal> balances = new HashMap<>();
        jdbc.sql(BALANCES.formatted("a.account_id = ANY (?)"))
                .params((Object) values)
                .query(JdbcAccountReadModel::toBalance)
                .list()
                .forEach(balance -> balances.put(balance.accountId(), balance.balance()));
        return balances;
    }

    /**
     * Every currency that has an account; the seed creates the Cobre accounts of each currency of
     * {@code cbmm.currencies}, so all of them appear, at 0.00 when they have no movement (§6.3).
     */
    @Override
    public SortedMap<CurrencyCode, BigDecimal> invariant() {
        SortedMap<CurrencyCode, BigDecimal> invariant = new TreeMap<>();
        jdbc.sql("""
                        SELECT a.currency,
                               COALESCE(SUM(a.balance), 0)
                               + COALESCE((SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)
                                           FROM entry_line l JOIN account c ON c.account_id = l.account_id
                                           WHERE c.currency = a.currency AND c.type <> 'CLIENT'), 0) AS total
                        FROM account a GROUP BY a.currency""")
                .query((rs, row) -> Map.entry(CurrencyCode.of(rs.getString("currency")), scale2(rs.getBigDecimal("total"))))
                .list()
                .forEach(entry -> invariant.put(entry.getKey(), entry.getValue()));
        return invariant;
    }

    @Override
    public Optional<EntryLinePage> linesOf(AccountId id, int limit, int offset) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("limit must be >= 1 and offset >= 0: " + limit + ", " + offset);
        }
        Optional<CurrencyCode> currency = jdbc.sql("SELECT currency FROM account WHERE account_id = ?")
                .params(id.value())
                .query((rs, row) -> CurrencyCode.of(rs.getString("currency")))
                .optional();
        if (currency.isEmpty()) {
            return Optional.empty();
        }
        long total = jdbc.sql("SELECT count(*) FROM entry_line WHERE account_id = ?")
                .params(id.value())
                .query(Long.class)
                .single();
        List<EntryLinePage.Line> lines = jdbc.sql("""
                        SELECT e.source_event_id, l.line_no, l.direction, l.amount, l.posted_at
                        FROM entry_line l JOIN journal_entry e ON e.entry_id = l.entry_id
                        WHERE l.account_id = ?
                        ORDER BY l.posted_at, e.source_event_id COLLATE "C", l.line_no
                        LIMIT ? OFFSET ?""")
                .params(id.value(), limit, offset)
                .query((rs, row) -> new EntryLinePage.Line(rs.getString("source_event_id"), rs.getInt("line_no"),
                        Direction.valueOf(rs.getString("direction")), scale2(rs.getBigDecimal("amount")),
                        rs.getTimestamp("posted_at").toInstant()))
                .list();
        return Optional.of(new EntryLinePage(id, currency.get(), total, limit, offset, lines));
    }

    private static AccountBalance toBalance(ResultSet rs, int row) throws SQLException {
        return new AccountBalance(AccountId.of(rs.getString("account_id")), AccountType.valueOf(rs.getString("type")),
                AccountStatus.valueOf(rs.getString("status")), CurrencyCode.of(rs.getString("currency")),
                scale2(rs.getBigDecimal("balance")));
    }

    /**
     * Columns are {@code numeric(19,2)} and their sums keep scale 2; {@code COALESCE(..., 0)} gives
     * scale 0. Only that case widens the scale: UNNECESSARY throws if a value ever had more decimals,
     * so nothing is rounded (D11).
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
