package com.cobre.accounts.adapters.out.persistence;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

/**
 * Which unique key a 23505 hit. The PostgreSQL driver is a runtime-only dependency, so its typed
 * {@code ServerErrorMessage} is not used: the constraint name, our own identifier from
 * {@code V1__accounts_schema.sql}, is looked for in the server message, whatever its language.
 */
final class UniqueViolation {

    static final String DECISION_PK = "settlement_decision_pk";
    static final String ENTRY_SOURCE_EVENT_UK = "journal_entry_source_event_uk";

    private UniqueViolation() {}

    static boolean on(DuplicateKeyException e, String constraint) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
