package com.handoffly.support.dto;

import com.handoffly.user.SubscriptionPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A support staff member's explicit, confirmed plan change: "this customer is on {@code fromPlan} and
 * should be on {@code toPlan}". Naming the plan they were looking at means a change made on a stale view
 * is refused instead of silently applied; none means the customer has no plan yet (a missing one is judged the same
 * way, so it can never apply a change to a customer who has one). Entitlements are not part of the request — they
 * follow the plan. Every change must say why: the reason is kept in the audit trail and the customer's subscription history.
 */
public record PlanChangeRequest(
        SubscriptionPlan fromPlan,
        @NotNull SubscriptionPlan toPlan,
        /** The last day the plan is paid for; none means the plan's own duration from now. Must not be in the past. */
        LocalDate validUntil,
        @NotBlank(message = "Give a reason for the plan change.") @Size(max = 500) String reason
) {}
