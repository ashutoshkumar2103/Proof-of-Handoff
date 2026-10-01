package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/**
 * Operation not allowed in the current state — e.g. an illegal status transition,
 * returning more than was handed out, or editing a CLOSED handoff.
 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "conflict", message);
    }
}
