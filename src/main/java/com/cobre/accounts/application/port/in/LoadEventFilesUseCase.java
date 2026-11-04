package com.cobre.accounts.application.port.in;

import java.util.List;
import java.util.function.Supplier;

/**
 * One load (glossary §4): the events of all its files in D4 order, each decided once (D6), with the
 * concurrency of D5 (spec §7.3 to §7.5). Loads run one at a time (AC16).
 */
public interface LoadEventFilesUseCase {

    /**
     * @param readFiles reads the files of the load; called only once the load holds the lock, so a
     *     refused load reads nothing (spec §7.3)
     * @throws LoadInProgressException if another load is running
     * @throws RuntimeException any exception of an event stops the load and is rethrown; events
     *     already decided are kept and sending the files again continues the load (spec §7.3, D6)
     */
    LoadResult load(Supplier<List<EventFile>> readFiles);
}
