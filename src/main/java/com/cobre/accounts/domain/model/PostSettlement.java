package com.cobre.accounts.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Command built by the anti-corruption layer from a CBMM event that passed rules 1 and 2 (spec
 * §7.1). It lives in the domain because {@code PostingService} consumes it and the domain cannot
 * depend on {@code application}. Invalid data here is a translator bug, hence exceptions.
 */
public record PostSettlement(
        String sourceEventId,
        Instant operationDate,
        AccountId originAccountId,
        Money originAmount,
        AccountId destinationAccountId,
        Money destinationAmount) {

    public PostSettlement {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(operationDate, "operationDate");
        Objects.requireNonNull(originAccountId, "originAccountId");
        Objects.requireNonNull(originAmount, "originAmount");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
        Objects.requireNonNull(destinationAmount, "destinationAmount");
        if (sourceEventId.isBlank()) {
            throw new InvalidIdentifierException("Settlement without source event id");
        }
        if (!originAmount.isPositive() || !destinationAmount.isPositive()) {
            throw new InvalidAmountException("Settlement " + sourceEventId + " amounts must be greater than 0: "
                    + originAmount + ", " + destinationAmount);
        }
        if (originAmount.currency().equals(destinationAmount.currency())) {
            throw new SameCurrencyLegsException(
                    "Settlement " + sourceEventId + " uses " + originAmount.currency() + " on both legs");
        }
    }
}
