package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/** Caller supplied invalid input that Bean Validation did not already reject. */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
