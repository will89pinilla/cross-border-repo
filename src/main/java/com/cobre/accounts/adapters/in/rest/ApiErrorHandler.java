package com.cobre.accounts.adapters.in.rest;

import com.cobre.accounts.application.port.in.LoadInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors as {@code application/problem+json} (RFC 9457) with the {@code code} of spec §8.2. Spring
 * MVC's own errors (unknown path, wrong media type) keep their status and standard problem. Anything
 * unexpected is 500 {@code INTERNAL_ERROR}: the stack trace goes to the log, never to the client.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    /** AC16: another load holds the lock, in this or another instance (spec §7.3). */
    @ExceptionHandler(LoadInProgressException.class)
    ResponseEntity<ProblemDetail> loadInProgress(LoadInProgressException e) {
        return problem(HttpStatus.CONFLICT, "LOAD_IN_PROGRESS", "Another load is in progress; try again when it ends");
    }

    /**
     * {@code max-file-size}, {@code max-request-size} and, as observed with Tomcat 11 and Spring 7
     * ({@code EventFilesApiErrorsTest}), also more parts than {@code server.tomcat.max-part-count}:
     * the limits of spec §6.2.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return payloadTooLarge(causedByPartCount(e) ? TOO_MANY_FILES : "The upload is above the size limits");
    }

    private static final String TOO_MANY_FILES = "The load has more files than allowed";

    /**
     * A multipart Tomcat refused to parse. Should the part-count limit ever arrive here instead of as
     * {@link MaxUploadSizeExceededException} (Tomcat's {@code FileCountLimitExceededException} in the
     * cause chain), it is still the files-per-load limit of §6.2: 413. Any other malformed multipart
     * carries no file that can be read: 400
     * {@code NO_FILES}, the closest code of §8.2 (the spec has no code of its own for it).
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> multipart(MultipartException e) {
        if (causedByPartCount(e)) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", TOO_MANY_FILES);
        }
        return problem(HttpStatus.BAD_REQUEST, "NO_FILES", "The request is not a valid multipart upload");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unexpected error answering the request", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error");
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<Object> payloadTooLarge(String detail) {
        return (ResponseEntity<Object>) (ResponseEntity<?>) problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                detail);
    }

    private static boolean causedByPartCount(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals("FileCountLimitExceededException")) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
