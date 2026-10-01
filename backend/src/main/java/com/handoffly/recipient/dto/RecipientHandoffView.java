package com.handoffly.recipient.dto;

import com.handoffly.attachment.AttachmentKind;
import com.handoffly.common.domain.ActorType;
import com.handoffly.handoff.HandoffStatus;
import com.handoffly.handoff.ItemCondition;
import com.handoffly.handoff.dto.HandoffItemResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Privacy-conscious projection of a handoff for the unauthenticated recipient. Excludes
 * owner account details and internal actor emails; exposes exactly what the recipient
 * needs to review, acknowledge and (later) record returns.
 */
public record RecipientHandoffView(
        String publicCode,
        String title,
        String purpose,
        String category,
        String senderName,
        String senderOrganization,
        String recipientName,
        HandoffStatus status,
        boolean awaitingResponse,
        boolean canRecordReturns,
        String acknowledgementName,
        Instant acknowledgedAt,
        String rejectionReason,
        Instant outgoingAt,
        Instant dueAt,
        BigDecimal totalOutgoing,
        BigDecimal totalRemaining,
        boolean missingToConfirm,
        Instant missingConfirmedAt,
        String missingConfirmedByName,
        Instant returnWaitRequestedAt,
        String returnWaitReason,
        String returnWaitRequestedByName,
        List<MissingItemView> missingItems,
        List<HandoffItemResponse> items,
        List<ReturnView> returns,
        List<AttachmentView> attachments
) {
    /** An item quantity reported missing, shown to the recipient for confirmation. */
    public record MissingItemView(String itemName, BigDecimal quantity) {}

    /** Attachment metadata safe to show a recipient (no uploader identity). */
    public record AttachmentView(
            Long id,
            AttachmentKind kind,
            String originalFilename,
            String contentType,
            long sizeBytes
    ) {}

    /** Return event summary safe to show a recipient (no actor emails). */
    public record ReturnView(
            Long id,
            Instant occurredAt,
            String note,
            boolean confirmed,
            ActorType enteredByType,
            List<ReturnLineView> lines
    ) {}

    public record ReturnLineView(
            String itemName,
            BigDecimal quantity,
            ItemCondition condition,
            String note
    ) {}
}
