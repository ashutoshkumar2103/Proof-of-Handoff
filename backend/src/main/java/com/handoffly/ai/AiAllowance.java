package com.handoffly.ai;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.RateLimitedException;
import com.handoffly.common.web.RateLimiter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How many requests to the AI provider one customer may make per hour — the free quota behind it is small and shared. Every AI feature spends
 * from this one allowance (the existing {@link RateLimiter}, under one key per customer), so there is a single setting for it
 * ({@code handoffly.rate-limit.ai-assist-per-user}) and one customer cannot use up the quota through several features at once.
 */
@Component
public class AiAllowance {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final RateLimiter limiter;
    private final HandOfflyProperties properties;

    public AiAllowance(RateLimiter limiter, HandOfflyProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * Counts one request for this customer.
     * @throws AiUnavailableException with {@link AiUnavailableException.Reason#RATE_LIMITED} once the hour's allowance is used up
     */
    public void spend(Long userId) {
        try {
            limiter.hit("ai-assist:" + userId, properties.getRateLimit().getAiAssistPerUser(), WINDOW);
        } catch (RateLimitedException e) {
            throw new AiUnavailableException(AiUnavailableException.Reason.RATE_LIMITED);
        }
    }
}
