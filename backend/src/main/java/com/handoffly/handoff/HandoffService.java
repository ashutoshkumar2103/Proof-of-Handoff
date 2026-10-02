package com.handoffly.handoff;

import com.handoffly.attachment.AttachmentRepository;
import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.AuditService;
import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.ForbiddenException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.web.PageResponse;
import com.handoffly.handoff.dto.CreateHandoffRequest;
import com.handoffly.handoff.dto.DashboardResponse;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemRequest;
import com.handoffly.handoff.dto.HandoffItemResponse;
import com.handoffly.handoff.dto.HandoffSummaryResponse;
import com.handoffly.handoff.dto.ReplaceItemsRequest;
import com.handoffly.handoff.dto.UpdateHandoffRequest;
import com.handoffly.notification.NotificationService;
import com.handoffly.recipient.RecipientLinkService;
import com.handoffly.returns.ReturnQueryService;
import com.handoffly.returns.dto.ReturnEventResponse;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the handoff lifecycle. This is the aggregate root for a handoff and the
 * only place status transitions are applied (always via {@link HandoffStateMachine}).
 * Reads assemble the full detail view from items, returns, attachments and audit events.
 */
@Service
public class HandoffService {

    private static final Set<HandoffStatus> OVERDUE_STATUSES = Set.of(
            HandoffStatus.ACTIVE_WITH_RECIPIENT,
            HandoffStatus.RETURN_PENDING,
            HandoffStatus.PARTIALLY_RETURNED);

    private final HandoffRepository handoffRepository;
    private final HandoffStateMachine stateMachine;
    private final HandoffMapper mapper;
    private final UserService userService;
    private final ReturnQueryService returnQueryService;
    private final AttachmentRepository attachmentRepository;
    private final AuditService auditService;
    private final RecipientLinkService recipientLinkService;
    private final NotificationService notificationService;

    public HandoffService(HandoffRepository handoffRepository,
                          HandoffStateMachine stateMachine,
                          HandoffMapper mapper,
                          UserService userService,
                          ReturnQueryService returnQueryService,
                          AttachmentRepository attachmentRepository,
                          AuditService auditService,
                          RecipientLinkService recipientLinkService,
                          NotificationService notificationService) {
        this.handoffRepository = handoffRepository;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.userService = userService;
        this.returnQueryService = returnQueryService;
        this.attachmentRepository = attachmentRepository;
        this.auditService = auditService;
        this.recipientLinkService = recipientLinkService;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------- Commands

    @Transactional
    public HandoffDetailResponse create(Long userId, CreateHandoffRequest request) {
        // The owner row stays locked until this transaction ends, so concurrent creations by the
        // same customer take the next number one at a time: AV-1, AV-2, AV-3 … with no duplicates.
        User owner = userService.getByIdForUpdate(userId);
        Handoff handoff = new Handoff(owner.nextHandoffReference(), owner, request.title().trim());
        applyHeader(handoff, request.purpose(), request.category(), request.senderName(),
                request.senderOrganization(), request.recipientName(), request.recipientEmail(),
                request.recipientPhone(), request.dueAt());
        if (request.items() != null) {
            for (HandoffItemRequest ir : request.items()) {
                handoff.addItem(toItem(ir));
            }
        }
        handoff = handoffRepository.save(handoff);
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_CREATED, ActorType.USER,
                owner.getEmail(), "Handoff created as draft.");
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse update(Long userId, Long handoffId, UpdateHandoffRequest request) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        requireDraft(handoff);
        applyHeader(handoff, request.purpose(), request.category(), request.senderName(),
                request.senderOrganization(), request.recipientName(), request.recipientEmail(),
                request.recipientPhone(), request.dueAt());
        handoff.setTitle(request.title().trim());
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_UPDATED, ActorType.USER,
                ownerRef(handoff), "Handoff details updated.");
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse replaceItems(Long userId, Long handoffId, ReplaceItemsRequest request) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        requireDraft(handoff);
        handoff.clearItems();
        for (HandoffItemRequest ir : request.items()) {
            handoff.addItem(toItem(ir));
        }
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_UPDATED, ActorType.USER,
                ownerRef(handoff), "Handoff items updated.");
        return toDetail(handoff);
    }

    @Transactional
    public void deleteDraft(Long userId, Long handoffId) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        if (!handoff.getStatus().isDraft()) {
            throw new ConflictException("Only draft handoffs can be deleted. Cancel it instead.");
        }
        handoffRepository.delete(handoff);
    }

    @Transactional
    public HandoffDetailResponse submit(Long userId, Long handoffId) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        requireDraft(handoff);
        if (handoff.getItems().isEmpty()) {
            throw new ConflictException("Add at least one item before submitting the handoff.");
        }
        transition(handoff, HandoffStatus.OUTGOING_SENT);
        handoff.setOutgoingAt(Instant.now());
        auditService.record(handoff.getId(), AuditEventType.OUTGOING_SUBMITTED, ActorType.USER,
                ownerRef(handoff), "Outgoing handoff submitted.");

        String rawToken = recipientLinkService.issue(handoff);
        notificationService.sendRecipientReviewLink(
                handoff.getRecipientEmail(), handoff.getRecipientName(), handoff.getSenderName(),
                handoff.getTitle(), handoff.getPublicCode(), rawToken);
        auditService.record(handoff.getId(), AuditEventType.RECIPIENT_LINK_SENT, ActorType.SYSTEM,
                null, "Secure review link emailed to " + handoff.getRecipientEmail() + ".");

        transition(handoff, HandoffStatus.AWAITING_RECIPIENT);
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse resendLink(Long userId, Long handoffId) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        if (handoff.getStatus() != HandoffStatus.AWAITING_RECIPIENT
                && handoff.getStatus() != HandoffStatus.OUTGOING_SENT) {
            throw new ConflictException("A review link can only be resent while awaiting the recipient.");
        }
        String rawToken = recipientLinkService.issue(handoff);
        notificationService.sendRecipientReviewLink(
                handoff.getRecipientEmail(), handoff.getRecipientName(), handoff.getSenderName(),
                handoff.getTitle(), handoff.getPublicCode(), rawToken);
        auditService.record(handoff.getId(), AuditEventType.RECIPIENT_LINK_SENT, ActorType.USER,
                ownerRef(handoff), "Secure review link re-sent to " + handoff.getRecipientEmail() + ".");
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse cancel(Long userId, Long handoffId, String reason) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        transition(handoff, HandoffStatus.CANCELLED);
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_CANCELLED, ActorType.USER,
                ownerRef(handoff), reasonMessage("Handoff cancelled", reason));
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse dispute(Long userId, Long handoffId, String reason) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        transition(handoff, HandoffStatus.DISPUTED);
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_DISPUTED, ActorType.USER,
                ownerRef(handoff), reasonMessage("Handoff marked as disputed", reason));
        return toDetail(handoff);
    }

    @Transactional
    public HandoffDetailResponse close(Long userId, Long handoffId, String reason) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        // Items that are neither returned nor accounted as missing block closing outright —
        // they must be returned (or marked missing) first.
        if (outstandingTotal(handoff).signum() > 0) {
            throw new ConflictException(
                    "This handoff still has items that haven't been returned. Record their return "
                            + "(or mark them missing) before closing.");
        }
        boolean hasMissing = returnQueryService.netMissingTotal(handoff).signum() > 0;
        if (hasMissing && handoff.getMissingConfirmedAt() == null) {
            throw new ConflictException(
                    "Some items are marked missing. Ask the recipient to confirm the missing items "
                            + "before closing this handoff.");
        }
        // Closing with missing (but recipient-confirmed) items is allowed, but a reason is required.
        String trimmedReason = reason == null ? null : reason.trim();
        if (hasMissing && (trimmedReason == null || trimmedReason.isEmpty())) {
            throw new BadRequestException(
                    "A reason is required to close a handoff that still has missing items.");
        }
        transition(handoff, HandoffStatus.CLOSED);
        auditService.record(handoff.getId(), AuditEventType.HANDOFF_CLOSED, ActorType.USER, ownerRef(handoff),
                hasMissing ? "Handoff closed with missing items. Reason: " + trimmedReason : "Handoff closed.");
        return toDetail(handoff);
    }

    /** Owner asks the recipient (via a fresh secure link) to confirm the missing items. */
    @Transactional
    public HandoffDetailResponse requestMissingConfirmation(Long userId, Long handoffId) {
        Handoff handoff = getOwnedHandoff(handoffId, userId);
        if (returnQueryService.netMissingTotal(handoff).signum() <= 0) {
            throw new ConflictException("No items are marked as missing on this handoff.");
        }
        if (handoff.getMissingConfirmedAt() != null) {
            throw new ConflictException("Missing items have already been confirmed.");
        }
        String rawToken = recipientLinkService.issue(handoff);
        notificationService.sendRecipientReviewLink(
                handoff.getRecipientEmail(), handoff.getRecipientName(), handoff.getSenderName(),
                handoff.getTitle(), handoff.getPublicCode(), rawToken);
        handoff.setMissingConfirmationRequestedAt(Instant.now());
        handoff.setReturnWaitRequestedAt(null);
        handoff.setReturnWaitReason(null);
        handoff.setReturnWaitRequestedByName(null);
        auditService.record(handoffId, AuditEventType.MISSING_CONFIRMATION_REQUESTED, ActorType.USER,
                ownerRef(handoff), "Requested recipient to confirm missing items.");
        return toDetail(handoff);
    }

    // ---------------------------------------------------------------- Queries

    @Transactional(readOnly = true)
    public HandoffDetailResponse getDetail(Long userId, Long handoffId) {
        return toDetail(getOwnedHandoff(handoffId, userId));
    }

    @Transactional(readOnly = true)
    public PageResponse<HandoffSummaryResponse> list(Long userId, Collection<HandoffStatus> statuses,
                                                     String query, Pageable pageable) {
        Specification<Handoff> spec = Specification.allOf(
                HandoffSpecifications.ownedBy(userId),
                HandoffSpecifications.statusIn(statuses),
                HandoffSpecifications.textSearch(query));
        Page<Handoff> page = handoffRepository.findAll(spec, pageable);
        return PageResponse.of(page, h ->
                mapper.toSummary(h,
                        returnQueryService.confirmedReturnedByItem(h.getId()),
                        returnQueryService.declaredMissingByItem(h.getId())));
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> getEvents(Long userId, Long handoffId) {
        getOwnedHandoff(handoffId, userId); // authorize
        return auditService.listForHandoff(handoffId);
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(Long userId) {
        Map<HandoffStatus, Long> counts = new EnumMap<>(HandoffStatus.class);
        long total = 0;
        for (HandoffRepository.StatusCount sc : handoffRepository.countByStatusForOwner(userId)) {
            counts.put(sc.getStatus(), sc.getCount());
            total += sc.getCount();
        }
        long overdue = handoffRepository.countByOwnerIdAndStatusInAndDueAtBefore(
                userId, OVERDUE_STATUSES, Instant.now());
        return new DashboardResponse(counts, overdue, total);
    }

    // ---------------------------------------------------- Cross-module helpers

    /** Loads a handoff (with items) and enforces owner access — used by other modules. */
    @Transactional(readOnly = true)
    public Handoff getOwnedHandoff(Long handoffId, Long userId) {
        Handoff handoff = handoffRepository.findWithItemsById(handoffId)
                .orElseThrow(() -> new NotFoundException("Handoff not found."));
        if (!handoff.isOwnedBy(userId)) {
            throw new ForbiddenException("You do not have access to this handoff.");
        }
        return handoff;
    }

    /** Applies a state transition after validating it against the state machine. */
    public void transition(Handoff handoff, HandoffStatus target) {
        stateMachine.assertCanTransition(handoff.getStatus(), target);
        handoff.setStatus(target);
    }

    /**
     * Recomputes and applies the return-driven status after a return is created or
     * confirmed. Called by the returns module. Only confirmed returns move the status.
     */
    @Transactional
    public void recomputeReturnStatus(Handoff handoff) {
        Map<Long, BigDecimal> returnedGood = returnQueryService.confirmedReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> pending = returnQueryService.pendingReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> declaredMissing = returnQueryService.declaredMissingByItem(handoff.getId());

        boolean anyAccounted = false;   // any returned-good or missing recorded
        boolean anyPending = false;
        boolean anyMissing = false;
        boolean allReturnedGood = !handoff.getItems().isEmpty();  // everything came back in good condition
        for (HandoffItem item : handoff.getItems()) {
            BigDecimal returned = returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal miss = ReturnQueryService.netMissing(item.getQuantity(), returned,
                    declaredMissing.getOrDefault(item.getId(), BigDecimal.ZERO));
            BigDecimal p = pending.getOrDefault(item.getId(), BigDecimal.ZERO);
            if (returned.signum() > 0 || miss.signum() > 0) anyAccounted = true;
            if (p.signum() > 0) anyPending = true;
            if (miss.signum() > 0) anyMissing = true;
            // Complete only if every unit came back (returned equals outgoing, nothing missing).
            if (item.getQuantity().subtract(returned).signum() > 0) allReturnedGood = false;
        }

        HandoffStatus target;
        if (allReturnedGood && !anyMissing) {
            // Everything physically returned in good condition — truly fully returned.
            target = HandoffStatus.FULLY_RETURNED;
        } else if (anyAccounted) {
            // Some returned and/or some missing → not fully returned.
            target = HandoffStatus.PARTIALLY_RETURNED;
        } else if (anyPending) {
            target = HandoffStatus.RETURN_PENDING;
        } else {
            return; // nothing to change
        }

        if (handoff.getStatus() != target && stateMachine.canTransition(handoff.getStatus(), target)) {
            handoff.setStatus(target);
            handoffRepository.save(handoff);
        }
    }

    // ---------------------------------------------------------------- Internals

    private HandoffItem toItem(HandoffItemRequest ir) {
        HandoffItem item = new HandoffItem(ir.name().trim(), ir.quantity());
        item.setDescription(trimToNull(ir.description()));
        item.setSku(trimToNull(ir.sku()));
        item.setSerialNumber(trimToNull(ir.serialNumber()));
        item.setAssetNumber(trimToNull(ir.assetNumber()));
        item.setUnit(trimToNull(ir.unit()));
        item.setNotes(trimToNull(ir.notes()));
        item.setCondition(ir.condition() == null ? ItemCondition.GOOD : ir.condition());
        return item;
    }

    private void applyHeader(Handoff handoff, String purpose, String category, String senderName,
                             String senderOrganization, String recipientName, String recipientEmail,
                             String recipientPhone, Instant dueAt) {
        handoff.setPurpose(trimToNull(purpose));
        handoff.setCategory(trimToNull(category));
        handoff.setSenderName(senderName.trim());
        handoff.setSenderOrganization(trimToNull(senderOrganization));
        handoff.setRecipientName(recipientName.trim());
        handoff.setRecipientEmail(recipientEmail.trim());
        handoff.setRecipientPhone(trimToNull(recipientPhone));
        handoff.setDueAt(dueAt);
    }

    private void requireDraft(Handoff handoff) {
        if (!handoff.getStatus().isDraft()) {
            throw new ConflictException("This handoff can no longer be edited (status: "
                    + handoff.getStatus() + ").");
        }
    }

    private HandoffDetailResponse toDetail(Handoff handoff) {
        Long id = handoff.getId();
        Map<Long, BigDecimal> returnedGood = returnQueryService.confirmedReturnedByItem(id);
        Map<Long, BigDecimal> pending = returnQueryService.pendingReturnedByItem(id);
        Map<Long, BigDecimal> declaredMissing = returnQueryService.declaredMissingByItem(id);

        List<HandoffItemResponse> items = mapper.itemResponses(handoff, returnedGood, pending, declaredMissing);
        List<ReturnEventResponse> returns = returnQueryService.eventsForHandoff(id).stream()
                .map(ReturnEventResponse::from).toList();
        List<AttachmentResponse> attachments = attachmentRepository
                .findByHandoffIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(AttachmentResponse::from).toList();
        var events = auditService.listForHandoff(id);

        // Outgoing = returned-good + missing + remaining (no double counting).
        BigDecimal totalOutgoing = BigDecimal.ZERO;
        BigDecimal totalReturned = BigDecimal.ZERO;
        BigDecimal totalMissing = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (HandoffItemResponse item : items) {
            totalOutgoing = totalOutgoing.add(item.outgoing());
            totalReturned = totalReturned.add(item.returnedConfirmed());
            totalMissing = totalMissing.add(item.missing());
            totalRemaining = totalRemaining.add(item.remaining());
        }
        boolean fullyReturned = handoff.getStatus() == HandoffStatus.FULLY_RETURNED
                || handoff.getStatus() == HandoffStatus.CLOSED;
        boolean hasPendingReturns = returns.stream().anyMatch(r -> !r.confirmed());
        boolean unconfirmedMissing = totalMissing.signum() > 0 && handoff.getMissingConfirmedAt() == null;
        // Closeable once nothing is outstanding and any missing has been confirmed.
        boolean closeable = totalRemaining.signum() == 0 && !unconfirmedMissing;

        return new HandoffDetailResponse(
                handoff.getId(), handoff.getPublicCode(), handoff.getTitle(), handoff.getPurpose(),
                handoff.getCategory(), handoff.getStatus(), handoff.getSenderName(),
                handoff.getSenderOrganization(), handoff.getRecipientName(), handoff.getRecipientEmail(),
                handoff.getRecipientPhone(), handoff.getAcknowledgementName(), handoff.getAcknowledgedAt(),
                handoff.getRejectionReason(), handoff.getOutgoingAt(), handoff.getAcceptanceAt(),
                handoff.getDueAt(), handoff.getCreatedAt(), handoff.getUpdatedAt(),
                totalOutgoing, totalReturned, totalRemaining, totalMissing,
                fullyReturned, mapper.isOverdue(handoff), unconfirmedMissing,
                handoff.getMissingConfirmationRequestedAt(), handoff.getMissingConfirmedAt(),
                handoff.getMissingConfirmedByName(),
                handoff.getReturnWaitRequestedAt(), handoff.getReturnWaitReason(),
                handoff.getReturnWaitRequestedByName(),
                availableActions(handoff, hasPendingReturns, unconfirmedMissing, closeable),
                items, returns, attachments, events);
    }

    /** Total quantity still outstanding (neither returned nor accounted as missing). */
    private BigDecimal outstandingTotal(Handoff handoff) {
        Map<Long, BigDecimal> returnedGood = returnQueryService.confirmedReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> declaredMissing = returnQueryService.declaredMissingByItem(handoff.getId());
        BigDecimal total = BigDecimal.ZERO;
        for (HandoffItem item : handoff.getItems()) {
            BigDecimal returned = returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal miss = ReturnQueryService.netMissing(item.getQuantity(), returned,
                    declaredMissing.getOrDefault(item.getId(), BigDecimal.ZERO));
            BigDecimal remaining = item.getQuantity().subtract(returned).subtract(miss);
            if (remaining.signum() > 0) total = total.add(remaining);
        }
        return total;
    }

    private List<String> availableActions(Handoff handoff, boolean hasPendingReturns,
                                          boolean unconfirmedMissing, boolean closeable) {
        List<HandoffAction> actions = new ArrayList<>();
        switch (handoff.getStatus()) {
            case DRAFT -> {
                actions.add(HandoffAction.EDIT);
                actions.add(HandoffAction.DELETE);
                actions.add(HandoffAction.SUBMIT);
                actions.add(HandoffAction.ADD_ATTACHMENT);
            }
            case OUTGOING_SENT, AWAITING_RECIPIENT -> {
                actions.add(HandoffAction.RESEND_LINK);
                actions.add(HandoffAction.CANCEL);
                actions.add(HandoffAction.ADD_ATTACHMENT);
            }
            case ACTIVE_WITH_RECIPIENT, RETURN_PENDING, PARTIALLY_RETURNED, OVERDUE -> {
                // Nothing left to return once everything is accounted (remaining 0 and any
                // missing confirmed) — only closing remains.
                if (!closeable) actions.add(HandoffAction.RECORD_RETURN);
                if (hasPendingReturns) actions.add(HandoffAction.CONFIRM_RETURN);
                if (unconfirmedMissing) actions.add(HandoffAction.REQUEST_MISSING_CONFIRMATION);
                else if (closeable) actions.add(HandoffAction.CLOSE);
                actions.add(HandoffAction.DISPUTE);
                actions.add(HandoffAction.ADD_ATTACHMENT);
            }
            case DISPUTED -> {
                if (!closeable) actions.add(HandoffAction.RECORD_RETURN);
                if (hasPendingReturns) actions.add(HandoffAction.CONFIRM_RETURN);
                if (unconfirmedMissing) actions.add(HandoffAction.REQUEST_MISSING_CONFIRMATION);
                else if (closeable) actions.add(HandoffAction.CLOSE);
                actions.add(HandoffAction.CANCEL);
                actions.add(HandoffAction.ADD_ATTACHMENT);
            }
            case FULLY_RETURNED -> {
                actions.add(HandoffAction.CLOSE);
                actions.add(HandoffAction.DISPUTE);
                actions.add(HandoffAction.ADD_ATTACHMENT);
            }
            case CLOSED, REJECTED, CANCELLED -> { /* read-only */ }
        }
        return actions.stream().map(Enum::name).toList();
    }

    private String ownerRef(Handoff handoff) {
        return handoff.getOwner().getEmail();
    }

    private String reasonMessage(String base, String reason) {
        return (reason == null || reason.isBlank()) ? base + "." : base + ": " + reason.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
