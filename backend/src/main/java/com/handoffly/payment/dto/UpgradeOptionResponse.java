package com.handoffly.payment.dto;

import com.handoffly.payment.PaymentService;
import com.handoffly.support.dto.PlanPriceResponse;
import com.handoffly.user.SubscriptionPlan;

/**
 * A plan the signed-in customer can move up to, and what it costs them: the plan's own price and what it includes, the plan they
 * are on ({@code from}) with its list price counted as already paid ({@code credit}), and the difference left to pay.
 * {@code handoffCheck} says whether the plan includes HandoffCheck, so a screen that exists to unlock it can offer only those
 * plans without knowing which they are.
 */
public record UpgradeOptionResponse(SubscriptionPlan from, PlanPriceResponse price, int credit, int amountDue, boolean handoffCheck) {
    public static UpgradeOptionResponse from(PaymentService.UpgradeOption option) {
        return new UpgradeOptionResponse(option.from(), PlanPriceResponse.from(option.plan()), option.credit(), option.amountDue(),
                option.plan().includesHandoffCheck());
    }
}
