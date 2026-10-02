package com.handoffly.support.dto;

import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SupportEntitlements;

/**
 * A plan's list price (what one payment is, how many months it covers) and what it includes from support.
 * Public information; the support phone number is never part of it.
 */
public record PlanPriceResponse(SubscriptionPlan plan, int months, int amount, String currency,
                                SupportEntitlements support) {
    public static PlanPriceResponse from(SubscriptionPlan plan) {
        return new PlanPriceResponse(plan, plan.months(), plan.amount(), SubscriptionPlan.CURRENCY,
                SupportEntitlements.of(plan, null));
    }
}
