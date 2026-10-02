package com.handoffly.support.dto;

import com.handoffly.user.SubscriptionChangeSource;
import com.handoffly.user.SubscriptionPlan;

import java.time.Instant;

/**
 * One plan a customer was moved to, read-only: from which plan to which, when it began and until when it is paid
 * for, when the change was made, and who or what made it ({@code staffCode} and {@code staffName} only for a change
 * support made). {@code previousPlan} and {@code validUntil} are null when there was none on record.
 */
public record SubscriptionHistoryResponse(
        SubscriptionPlan previousPlan,
        SubscriptionPlan newPlan,
        Instant startsAt,
        Instant validUntil,
        Instant changedAt,
        SubscriptionChangeSource source,
        String staffCode,
        String staffName,
        String reason
) {}
