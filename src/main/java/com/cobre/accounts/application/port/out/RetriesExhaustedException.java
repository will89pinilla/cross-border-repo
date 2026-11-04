package com.cobre.accounts.application.port.out;

/**
 * The third attempt also failed with a deadlock or a serialization failure (spec §7.3). The load is
 * interrupted with 500; events already decided are kept.
 */
public class RetriesExhaustedException extends RuntimeException {

    private final int attempts;

    public RetriesExhaustedException(int attempts, Throwable cause) {
        super("Transaction failed after " + attempts + " attempts", cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
