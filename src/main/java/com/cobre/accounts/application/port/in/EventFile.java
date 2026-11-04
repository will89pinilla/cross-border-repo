package com.cobre.accounts.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * One uploaded file after reading (spec §6.2). An {@code UNREADABLE} file has no events.
 *
 * @param events in file order; {@code events_read} of the report is its size
 */
public record EventFile(String name, FileStatus status, List<ReadEvent> events) {

    public EventFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        events = List.copyOf(events);
        if (status == FileStatus.UNREADABLE && !events.isEmpty()) {
            throw new IllegalArgumentException("An unreadable file has no events: " + name);
        }
    }

    public static EventFile unreadable(String name) {
        return new EventFile(name, FileStatus.UNREADABLE, List.of());
    }
}
