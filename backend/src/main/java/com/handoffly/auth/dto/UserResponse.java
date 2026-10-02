package com.handoffly.auth.dto;

import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SupportEntitlements;
import com.handoffly.user.User;

import java.time.Instant;

/**
 * The signed-in user as the apps see it. {@code accountCode} is the customer-facing Account ID;
 * {@code support} is derived from the plan so the UI only offers what the plan includes.
 */
public record UserResponse(
        Long id,
        String accountCode,
        String email,
        String displayName,
        String organization,
        String phone,
        SubscriptionPlan plan,
        Instant createdAt,
        SupportEntitlements support
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
                user.getCreatedAt(),
                SupportEntitlements.of(user.getSubscriptionPlan(), configuredSupportPhone));
    }
}
