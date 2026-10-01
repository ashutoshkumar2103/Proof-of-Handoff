package com.handoffly.handoff.dto;

import com.handoffly.handoff.HandoffStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Compact handoff view for lists and the dashboard, with aggregate outgoing/remaining
 * totals so the table can show Outgoing / Remaining together.
 */
public record HandoffSummaryResponse(
        Long id,
        String publicCode,
        String title,
        String category,
        HandoffStatus status,
        String senderName,
        String recipientName,
        String recipientEmail,
        int itemCount,
        BigDecimal totalOutgoing,
        BigDecimal totalReturned,
        BigDecimal totalMissing,
        BigDecimal totalRemaining,
        boolean overdue,
        Instant outgoingAt,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt
) {}
