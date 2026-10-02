package com.handoffly.user;

import java.time.Instant;

/**
 * A customer's subscription as the apps show it: the plan, whether it is currently paid up, and its dates.
 * {@code plan} is null for an account that has none yet (nothing was paid for or activated: status INACTIVE);
 * {@code startedAt} is null for accounts whose start was never recorded; {@code validUntil} is null for a plan
 * with no end date.
 */
public record SubscriptionSummary(SubscriptionPlan plan, SubscriptionStatus status, Instant startedAt, Instant validUntil) {

    public static SubscriptionSummary of(User user, Instant now) {
        return new SubscriptionSummary(user.getSubscriptionPlan(), user.subscriptionStatus(now),
                user.getPlanStartedAt(), user.getPlanValidUntil());
    }
}
