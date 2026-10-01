package com.handoffly.handoff.dto;

import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.handoff.HandoffStatus;
import com.handoffly.returns.dto.ReturnEventResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Full handoff view: header, outgoing items with computed remaining, all return events,
 * attachments, and the lifecycle event history — everything shown on the detail page.
 */
public record HandoffDetailResponse(
        Long id,
        String publicCode,
        String title,
        String purpose,
        String category,
        HandoffStatus status,
        String senderName,
        String senderOrganization,
        String recipientName,
        String recipientEmail,
        String recipientPhone,
        String acknowledgementName,
        Instant acknowledgedAt,
        String rejectionReason,
        Instant outgoingAt,
        Instant acceptanceAt,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        BigDecimal totalOutgoing,
        BigDecimal totalReturned,
        BigDecimal totalRemaining,
        BigDecimal totalMissing,
        boolean fullyReturned,
        boolean overdue,
        boolean hasUnconfirmedMissing,
        Instant missingConfirmationRequestedAt,
        Instant missingConfirmedAt,
        String missingConfirmedByName,
        Instant returnWaitRequestedAt,
        String returnWaitReason,
        String returnWaitRequestedByName,
        List<String> availableActions,
        List<HandoffItemResponse> items,
        List<ReturnEventResponse> returns,
        List<AttachmentResponse> attachments,
        List<AuditEventResponse> events
) {}
