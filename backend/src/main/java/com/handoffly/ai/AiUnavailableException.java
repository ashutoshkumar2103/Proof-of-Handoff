package com.handoffly.ai;

/**
 * The AI provider could not give an answer (not set up, unreachable, too slow, over quota, or it answered with something that is
 * not usable). Carries only a {@link Reason}: never the provider's own message, which is for the log and not for a customer.
 */
public class AiUnavailableException extends RuntimeException {

    public enum Reason { NOT_CONFIGURED, UNAVAILABLE, RATE_LIMITED, INVALID_RESPONSE, NOTHING_FOUND }

    private final Reason reason;

    public AiUnavailableException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
