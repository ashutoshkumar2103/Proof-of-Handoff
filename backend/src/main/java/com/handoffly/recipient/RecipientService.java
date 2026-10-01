package com.handoffly.recipient;

import com.handoffly.attachment.Attachment;
import com.handoffly.attachment.AttachmentRepository;
import com.handoffly.attachment.AttachmentService;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.AuditService;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.ConflictException;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffMapper;
import com.handoffly.handoff.HandoffService;
import com.handoffly.handoff.HandoffStatus;
import com.handoffly.recipient.dto.AcceptHandoffRequest;
import com.handoffly.recipient.dto.RecipientHandoffView;
import com.handoffly.recipient.dto.RejectHandoffRequest;
import com.handoffly.recipient.dto.ReturnWaitRequest;
import com.handoffly.returns.ReturnEvent;
import com.handoffly.returns.ReturnQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the unauthenticated recipient experience, always scoped to a single handoff via
 * an opaque token. Applies acceptance/rejection and delegates returns to the returns
 * module. Never exposes owner account details.
 */
@Service
public class RecipientService {

    private final RecipientLinkService recipientLinkService;
    private final HandoffService handoffService;
    private final ReturnQueryService returnQueryService;
    private final HandoffMapper handoffMapper;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentService attachmentService;
    private final AuditService auditService;

    public RecipientService(RecipientLinkService recipientLinkService,
                            HandoffService handoffService,
                            ReturnQueryService returnQueryService,
                            HandoffMapper handoffMapper,
                            AttachmentRepository attachmentRepository,
                            AttachmentService attachmentService,
                            AuditService auditService) {
        this.recipientLinkService = recipientLinkService;
        this.handoffService = handoffService;
        this.returnQueryService = returnQueryService;
        this.handoffMapper = handoffMapper;
        this.attachmentRepository = attachmentRepository;
        this.attachmentService = attachmentService;
        this.auditService = auditService;
    }

    /** Loads an attachment for download, scoped to the token's own handoff. */
    @Transactional(readOnly = true)
    public AttachmentService.LoadedAttachment downloadAttachment(String token, Long attachmentId) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        return attachmentService.load(link.getHandoff().getId(), attachmentId);
    }

    @Transactional
    public RecipientHandoffView view(String token) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        Handoff handoff = link.getHandoff();
        if (recipientLinkService.markOpenedIfFirst(link)) {
            auditService.record(handoff.getId(), AuditEventType.RECIPIENT_OPENED, ActorType.RECIPIENT,
                    handoff.getRecipientName(), "Recipient opened the review link.");
        }
        return toView(handoff);
    }

    @Transactional
    public RecipientHandoffView accept(String token, AcceptHandoffRequest request) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        Handoff handoff = link.getHandoff();
        requireAwaiting(handoff);
        handoffService.transition(handoff, HandoffStatus.ACTIVE_WITH_RECIPIENT);
        Instant now = Instant.now();
        handoff.setAcknowledgementName(request.acknowledgementName().trim());
        handoff.setAcknowledgedAt(now);
        handoff.setAcceptanceAt(now);
        auditService.record(handoff.getId(), AuditEventType.RECIPIENT_ACCEPTED, ActorType.RECIPIENT,
                request.acknowledgementName().trim(),
                "Recipient accepted and acknowledged the handoff.");
        return toView(handoff);
    }

    @Transactional
    public RecipientHandoffView reject(String token, RejectHandoffRequest request) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        Handoff handoff = link.getHandoff();
        requireAwaiting(handoff);
        handoffService.transition(handoff, HandoffStatus.REJECTED);
        handoff.setAcknowledgementName(request.acknowledgementName().trim());
        handoff.setAcknowledgedAt(Instant.now());
        handoff.setRejectionReason(request.reason() == null ? null : request.reason().trim());
        auditService.record(handoff.getId(), AuditEventType.RECIPIENT_REJECTED, ActorType.RECIPIENT,
                request.acknowledgementName().trim(),
                request.reason() == null || request.reason().isBlank()
                        ? "Recipient rejected the handoff."
                        : "Recipient rejected the handoff: " + request.reason().trim());
        return toView(handoff);
    }

    /** Recipient confirms the items reported missing, unblocking closure by the sender. */
    @Transactional
    public RecipientHandoffView confirmMissing(String token, AcceptHandoffRequest request) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        Handoff handoff = link.getHandoff();
        if (handoff.getMissingConfirmationRequestedAt() == null) {
            throw new ConflictException("No missing-item confirmation was requested for this handoff.");
        }
        if (handoff.getMissingConfirmedAt() != null) {
            throw new ConflictException("The missing items have already been confirmed.");
        }
        String name = request.acknowledgementName().trim();
        handoff.setMissingConfirmedAt(Instant.now());
        handoff.setMissingConfirmedByName(name);
        auditService.record(handoff.getId(), AuditEventType.MISSING_CONFIRMED, ActorType.RECIPIENT,
                name, "Recipient confirmed the missing items (acknowledged by " + name + ").");
        return toView(handoff);
    }

    /** Recipient requests to wait for return instead of confirming missing. */
    @Transactional
    public RecipientHandoffView requestReturnWait(String token, ReturnWaitRequest request) {
        RecipientLink link = recipientLinkService.resolveUsable(token);
        Handoff handoff = link.getHandoff();
        if (handoff.getMissingConfirmationRequestedAt() == null) {
            throw new ConflictException("No missing-item confirmation was requested for this handoff.");
        }
        if (handoff.getMissingConfirmedAt() != null) {
            throw new ConflictException("The missing items have already been confirmed.");
        }
        String name = request.acknowledgementName().trim();
        String reason = request.reason().trim();
        handoff.setReturnWaitRequestedAt(Instant.now());
        handoff.setReturnWaitReason(reason);
        handoff.setReturnWaitRequestedByName(name);
        auditService.record(handoff.getId(), AuditEventType.RETURN_WAIT_REQUESTED, ActorType.RECIPIENT,
                name, "Recipient requested to wait for return: " + reason);
        return toView(handoff);
    }

    // --------------------------------------------------------------- Internals

    private void requireAwaiting(Handoff handoff) {
        if (handoff.getStatus() != HandoffStatus.AWAITING_RECIPIENT) {
            throw new ConflictException("This handoff has already been responded to.");
        }
    }

    private RecipientHandoffView toView(Handoff handoff) {
        Long id = handoff.getId();
        Map<Long, BigDecimal> returnedGood = returnQueryService.confirmedReturnedByItem(id);
        Map<Long, BigDecimal> pending = returnQueryService.pendingReturnedByItem(id);
        Map<Long, BigDecimal> declaredMissing = returnQueryService.declaredMissingByItem(id);
        // Net missing (declared minus anything since returned) is what the recipient confirms.
        Map<Long, BigDecimal> missingByItem = returnQueryService.netMissingByItem(handoff);

        var items = handoffMapper.itemResponses(handoff, returnedGood, pending, declaredMissing);
        BigDecimal totalOutgoing = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (var item : items) {
            totalOutgoing = totalOutgoing.add(item.outgoing());
            totalRemaining = totalRemaining.add(item.remaining());
        }

        List<RecipientHandoffView.ReturnView> returns = returnQueryService.eventsForHandoff(id).stream()
                .map(this::toReturnView).toList();
        List<RecipientHandoffView.AttachmentView> attachments = attachmentRepository
                .findByHandoffIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(this::toAttachmentView).toList();

        Map<Long, String> itemNames = new HashMap<>();
        handoff.getItems().forEach(it -> itemNames.put(it.getId(), it.getName()));
        List<RecipientHandoffView.MissingItemView> missingItems = missingByItem.entrySet().stream()
                .map(e -> new RecipientHandoffView.MissingItemView(
                        itemNames.getOrDefault(e.getKey(), "Item"), e.getValue()))
                .toList();
        boolean missingToConfirm = handoff.getMissingConfirmationRequestedAt() != null
                && handoff.getMissingConfirmedAt() == null
                && handoff.getReturnWaitRequestedAt() == null
                && !missingItems.isEmpty();

        return new RecipientHandoffView(
                handoff.getPublicCode(), handoff.getTitle(), handoff.getPurpose(), handoff.getCategory(),
                handoff.getSenderName(), handoff.getSenderOrganization(), handoff.getRecipientName(),
                handoff.getStatus(),
                handoff.getStatus() == HandoffStatus.AWAITING_RECIPIENT,
                handoff.getStatus().isActiveWithRecipient(),
                handoff.getAcknowledgementName(), handoff.getAcknowledgedAt(), handoff.getRejectionReason(),
                handoff.getOutgoingAt(), handoff.getDueAt(),
                totalOutgoing, totalRemaining,
                missingToConfirm, handoff.getMissingConfirmedAt(), handoff.getMissingConfirmedByName(),
                handoff.getReturnWaitRequestedAt(), handoff.getReturnWaitReason(),
                handoff.getReturnWaitRequestedByName(),
                missingItems, items, returns, attachments);
    }

    private RecipientHandoffView.ReturnView toReturnView(ReturnEvent event) {
        List<RecipientHandoffView.ReturnLineView> lines = event.getLines().stream()
                .map(l -> new RecipientHandoffView.ReturnLineView(
                        l.getItem().getName(), l.getQuantity(), l.getCondition(), l.getNote()))
                .toList();
        return new RecipientHandoffView.ReturnView(
                event.getId(), event.getOccurredAt(), event.getNote(),
                event.isConfirmed(), event.getEnteredByType(), lines);
    }

    private RecipientHandoffView.AttachmentView toAttachmentView(Attachment a) {
        return new RecipientHandoffView.AttachmentView(
                a.getId(), a.getKind(), a.getOriginalFilename(), a.getContentType(), a.getSizeBytes());
    }
}
