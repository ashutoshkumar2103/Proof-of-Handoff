package com.handoffly.common.error;

import org.springframework.http.HttpStatus;

/** Too many requests of one kind in a short time. Carries how long to wait, sent as {@code Retry-After}. */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts. Please wait a while and try again.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
