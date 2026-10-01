package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base for all application exceptions that map cleanly to an HTTP status and a
 * safe, user-facing message. Handled centrally by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
