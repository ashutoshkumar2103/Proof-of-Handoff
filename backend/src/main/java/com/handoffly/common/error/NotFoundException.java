package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/** Requested resource does not exist (or is not visible to the caller). */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "not_found", message);
    }
}
