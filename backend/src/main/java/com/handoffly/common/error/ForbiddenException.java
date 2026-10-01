package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/** Caller is authenticated but not permitted to act on this resource. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "forbidden", message);
    }
}
