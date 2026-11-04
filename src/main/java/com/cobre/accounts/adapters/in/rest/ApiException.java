package com.cobre.accounts.adapters.in.rest;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * An expected error of the API with its status and the {@code code} of spec §8.2. Its message goes
 * into the problem's {@code detail}, so it must never carry internal information.
 */
final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
    }

    static ApiException noFiles() {
        return new ApiException(HttpStatus.BAD_REQUEST, "NO_FILES", "The multipart field 'files' has no file");
    }

    static ApiException accountNotFound(String accountId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
    }

    static ApiException invalidPagination() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                "limit must be an integer between 1 and 1000 and offset an integer of at least 0");
    }

    static ApiException tooManyEvents(long maxEvents) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "The load has more than " + maxEvents + " events");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
