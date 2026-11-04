package com.cobre.accounts.application.port.in;

/** Status of a file in the load report (spec §8.1). */
public enum FileStatus {
    PROCESSED,
    /** Not valid JSON as a whole (spec §6.2): none of its events is applied. */
    UNREADABLE
}
