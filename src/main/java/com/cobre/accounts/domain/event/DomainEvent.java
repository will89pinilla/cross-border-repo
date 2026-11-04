package com.cobre.accounts.domain.event;

/**
 * Result events of spec §8.3, emitted after the commit of the event's transaction (§7.3, step 6).
 * In the whole system they would go back to Component A.
 */
public sealed interface DomainEvent permits EntriesPosted, PostingRejected {

    String sourceEventId();
}
