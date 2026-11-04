package com.cobre.accounts.application.port.out;

import com.cobre.accounts.domain.event.DomainEvent;

/**
 * Emits the result events of spec §8.3. Called only after the commit (§7.3, step 6), never inside
 * {@link UnitOfWork}: a retried transaction must not emit twice.
 *
 * <p>The decision is already committed when this runs, so an implementation must not throw: a
 * failure to emit is its own problem to log, not a reason to lose the event's outcome.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
