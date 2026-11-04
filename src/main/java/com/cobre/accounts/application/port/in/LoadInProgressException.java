package com.cobre.accounts.application.port.in;

/** Another load is running, in this or another instance: 409 {@code LOAD_IN_PROGRESS} (spec §8.2, AC16). */
public final class LoadInProgressException extends RuntimeException {

    public LoadInProgressException() {
        super("Another load is in progress");
    }
}
