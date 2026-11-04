package com.cobre.accounts.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Three uppercase letters. Only the format is checked here: which currencies are supported is
 * configuration ({@code cbmm.currencies}) and is validated by the file reader (spec §6.1).
 */
public record CurrencyCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}");

    public CurrencyCode {
        Objects.requireNonNull(value, "currency");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidIdentifierException("Currency must be three uppercase letters: " + value);
        }
    }

    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
