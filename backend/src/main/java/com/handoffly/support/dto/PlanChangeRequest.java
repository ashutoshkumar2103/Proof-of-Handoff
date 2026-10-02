package com.handoffly.support.dto;

import com.handoffly.user.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A support staff member's explicit, confirmed plan change: "this customer is on {@code fromPlan} and
 * should be on {@code toPlan}". Naming the plan they were looking at means a change made on a stale view
 * is refused instead of silently applied. Entitlements are not part of the request — they follow the plan.
 */
public record PlanChangeRequest(
        @NotNull SubscriptionPlan fromPlan,
        @NotNull SubscriptionPlan toPlan,
        @Size(max = 500) String reason
) {}
