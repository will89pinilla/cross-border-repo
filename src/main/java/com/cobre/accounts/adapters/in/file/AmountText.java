package com.cobre.accounts.adapters.in.file;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Checks the decimal text of an {@code amount} (spec §6.1, §7.2) without binary floating point and
 * in linear time, before any {@link BigDecimal} is built.
 *
 * <p>The grammar is the JSON number one: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}.
 * Digits are counted on the text, with leading and trailing zeros dropped, so {@code "10.500"} is
 * two decimals, and a huge text or exponent ({@code "1." + a million zeros}, {@code 1E+999999999})
 * is classified without expanding it. Nothing is ever rounded (D11).
 */
final class AmountText {

    static final int MAX_DECIMALS = 2;
    static final int MAX_INTEGER_DIGITS = 17;

    /** Saturation for the exponent. Texts are far shorter than this, so comparisons stay exact. */
    private static final long EXPONENT_CAP = 1_000_000_000_000L;

    private AmountText() {}

    /** Either a positive amount with at most 2 decimals and 17 integer digits, or the failing detail. */
    sealed interface Result {}

    record Valid(BigDecimal amount) implements Result {}

    record Failed(InvalidEventDetail detail) implements Result {}

    static Result parse(String text) {
        int n = text.length();
        int i = 0;
        boolean negative = false;
        if (i < n && text.charAt(i) == '-') {
            negative = true;
            i++;
        }
        int intStart = i;
        while (i < n && isDigit(text.charAt(i))) {
            i++;
        }
        int intLen = i - intStart;
        if (intLen == 0 || (intLen > 1 && text.charAt(intStart) == '0')) {
            return failed(InvalidEventDetail.INVALID_FORMAT);
        }
        int fracStart = i;
        int fracLen = 0;
        if (i < n && text.charAt(i) == '.') {
            i++;
            fracStart = i;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
            fracLen = i - fracStart;
            if (fracLen == 0) {
                return failed(InvalidEventDetail.INVALID_FORMAT);
            }
        }
        long exponent = 0;
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            boolean exponentNegative = false;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                exponentNegative = text.charAt(i) == '-';
                i++;
            }
            int expStart = i;
            while (i < n && isDigit(text.charAt(i))) {
                if (exponent < EXPONENT_CAP) {
                    exponent = exponent * 10 + (text.charAt(i) - '0');
                }
                i++;
            }
            if (i == expStart) {
                return failed(InvalidEventDetail.INVALID_FORMAT);
            }
            exponent = Math.min(exponent, EXPONENT_CAP);
            if (exponentNegative) {
                exponent = -exponent;
            }
        }
        if (i != n) {
            return failed(InvalidEventDetail.INVALID_FORMAT);
        }

        // The digits of the integer and fraction parts, read as one sequence D: value = D x 10^(exponent - fracLen).
        Digits digits = new Digits(text, intStart, intLen, fracStart, fracLen);
        int total = intLen + fracLen;
        int leadingZeros = 0;
        while (leadingZeros < total && digits.at(leadingZeros) == '0') {
            leadingZeros++;
        }
        if (negative || leadingZeros == total) {
            return failed(InvalidEventDetail.NON_POSITIVE_AMOUNT);
        }
        int trailingZeros = 0;
        while (digits.at(total - 1 - trailingZeros) == '0') {
            trailingZeros++;
        }
        int significant = total - leadingZeros - trailingZeros;
        // value = S x 10^e, with S the significant digits (no leading or trailing zeros).
        long e = exponent - fracLen + trailingZeros;
        long decimals = e < 0 ? -e : 0;
        if (decimals > MAX_DECIMALS) {
            return failed(InvalidEventDetail.TOO_MANY_DECIMALS);
        }
        long integerDigits = significant + e;
        if (integerDigits > MAX_INTEGER_DIGITS) {
            return failed(InvalidEventDetail.AMOUNT_TOO_LARGE);
        }
        // Here S has at most 19 digits, so building it is cheap whatever the text length was.
        StringBuilder unscaled = new StringBuilder(significant);
        for (int k = leadingZeros; k < total - trailingZeros; k++) {
            unscaled.append(digits.at(k));
        }
        return new Valid(new BigDecimal(new BigInteger(unscaled.toString()), (int) -e));
    }

    private static Failed failed(InvalidEventDetail detail) {
        return new Failed(detail);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private record Digits(String text, int intStart, int intLen, int fracStart, int fracLen) {

        char at(int index) {
            return index < intLen ? text.charAt(intStart + index) : text.charAt(fracStart + index - intLen);
        }
    }
}
