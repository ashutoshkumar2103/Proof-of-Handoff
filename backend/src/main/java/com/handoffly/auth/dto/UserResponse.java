package com.handoffly.auth.dto;

import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SubscriptionSummary;
import com.handoffly.user.SupportEntitlements;
import com.handoffly.user.User;

import java.time.Instant;

/**
 * The signed-in user as the apps see it. {@code accountCode} is the customer-facing Account ID;
 * {@code plan} is null for an account nothing has been activated on yet;
 * {@code support} is derived from the plan so the UI only offers what the plan includes, and {@code handoffCheck} says
 * whether the plan includes HandoffCheck (a product feature, kept apart from the support entitlements).
 */
public record UserResponse(
        Long id,
        String accountCode,
        String email,
        String displayName,
        String organization,
        String phone,
        SubscriptionPlan plan,
        String handoffPrefix,
        Instant createdAt,
        SupportEntitlements support,
        SubscriptionSummary subscription,
        boolean handoffCheck
) {
    public static UserResponse from(User user, String configuredSupportPhone) {
        return new UserResponse(
                user.getId(),
                user.getAccountCode(),
                user.getEmail(),
                user.getDisplayName(),
                user.getOrganization(),
                user.getPhone(),
                user.getSubscriptionPlan(),
                user.getHandoffPrefix(),
                user.getCreatedAt(),
                // What the plan includes only counts while the subscription is active (see User#entitledPlan); an account with
                // no plan gets the activation help.
                SupportEntitlements.of(user, configuredSupportPhone),
                SubscriptionSummary.of(user, Instant.now()),
                user.entitledPlan().includesHandoffCheck());
    }
}
