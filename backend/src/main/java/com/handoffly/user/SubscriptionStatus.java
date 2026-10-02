package com.handoffly.user;

/**
 * Whether a customer's subscription is currently paid up. Not stored: it follows from the plan's valid-until
 * date ({@link User#subscriptionStatus}), so it cannot disagree with it. A plan with no end date is ACTIVE; an account
 * that has no plan at all is INACTIVE ({@link User#hasPlan()} tells that apart from a plan that ran out).
 */
public enum SubscriptionStatus {
    ACTIVE,
    INACTIVE
}
