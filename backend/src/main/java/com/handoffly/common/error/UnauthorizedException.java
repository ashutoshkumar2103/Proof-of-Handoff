package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/** Authentication is missing or invalid. */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }
}
