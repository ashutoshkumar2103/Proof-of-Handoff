package com.handoffly.support.dto;

import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SubscriptionStatus;
import com.handoffly.user.User;

import java.time.Instant;

/** A customer as support sees them: identity, plan and numbering prefix — no business data. */
public record CustomerSummaryResponse(
        String accountCode,
        String name,
        String email,
        String phone,
        SubscriptionPlan plan,
        SubscriptionStatus subscriptionStatus,
        Instant planValidUntil,
        String handoffPrefix,
        Instant createdAt
) {
    public static CustomerSummaryResponse from(User u) {
        return new CustomerSummaryResponse(
                u.getAccountCode(), u.getDisplayName(), u.getEmail(), u.getPhone(),
                u.getSubscriptionPlan(), u.subscriptionStatus(Instant.now()), u.getPlanValidUntil(),
                u.getHandoffPrefix(), u.getCreatedAt());
    }
}
