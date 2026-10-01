package com.handoffly.returns;

import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.AuditService;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffItem;
import com.handoffly.handoff.HandoffService;
import com.handoffly.handoff.ItemCondition;
import com.handoffly.returns.dto.CreateReturnRequest;
import com.handoffly.returns.dto.ReturnEventResponse;
import com.handoffly.returns.dto.ReturnLineRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Records and confirms returns against a handoff, always as new events on the SAME
 * handoff (outgoing quantities are never mutated). Enforces that total claimed returns
 * (pending + confirmed) never exceed the outgoing quantity of any item.
 */
@Service
public class ReturnService {

    private final ReturnEventRepository returnEventRepository;
    private final ReturnQueryService returnQueryService;
    private final HandoffService handoffService;
    private final AuditService auditService;

    public ReturnService(ReturnEventRepository returnEventRepository,
                         ReturnQueryService returnQueryService,
                         HandoffService handoffService,
                         AuditService auditService) {
        this.returnEventRepository = returnEventRepository;
        this.returnQueryService = returnQueryService;
        this.handoffService = handoffService;
        this.auditService = auditService;
    }

    /** Owner records a return — auto-confirmed since the owner physically received it. */
    @Transactional
    public ReturnEventResponse createByOwner(Long userId, Long handoffId, CreateReturnRequest request) {
        Handoff handoff = handoffService.getOwnedHandoff(handoffId, userId);
        return create(handoff, request, ActorType.USER, handoff.getOwner().getEmail(), true);
    }

    @Transactional
    public ReturnEventResponse confirm(Long userId, Long handoffId, Long returnEventId) {
        Handoff handoff = handoffService.getOwnedHandoff(handoffId, userId);
        ReturnEvent event = returnEventRepository.findWithLinesById(returnEventId)
                .orElseThrow(() -> new NotFoundException("Return not found."));
        if (!event.getHandoff().getId().equals(handoff.getId())) {
            throw new NotFoundException("Return not found.");
        }
        if (event.isConfirmed()) {
            throw new ConflictException("This return has already been confirmed.");
        }
        event.setConfirmed(true);
        event.setConfirmedAt(Instant.now());
        event.setConfirmedByRef(handoff.getOwner().getEmail());
        auditService.record(handoff.getId(), AuditEventType.RETURN_CONFIRMED, ActorType.USER,
                handoff.getOwner().getEmail(), "Return confirmed.");
        handoffService.recomputeReturnStatus(handoff);
        return ReturnEventResponse.from(event);
    }

    // ------------------------------------------------------------- Internals

    private ReturnEventResponse create(Handoff handoff, CreateReturnRequest request,
                                       ActorType actorType, String actorRef, boolean autoConfirm) {
        if (!handoff.getStatus().isActiveWithRecipient()) {
            throw new ConflictException(
                    "Returns can only be recorded once the recipient has accepted the handoff.");
        }

        Map<Long, HandoffItem> itemsById = new HashMap<>();
        for (HandoffItem item : handoff.getItems()) {
            itemsById.put(item.getId(), item);
        }
        Map<Long, BigDecimal> returnedGood = returnQueryService.confirmedReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> pending = returnQueryService.pendingReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> declaredMissing = returnQueryService.declaredMissingByItem(handoff.getId());
        Map<Long, BigDecimal> returnedInThisRequest = new HashMap<>();
        Map<Long, BigDecimal> missingInThisRequest = new HashMap<>();

        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
        ReturnEvent event = new ReturnEvent(handoff, occurredAt, actorType, actorRef);
        event.setNote(trimToNull(request.note()));

        for (ReturnLineRequest line : request.lines()) {
            HandoffItem item = itemsById.get(line.itemId());
            if (item == null) {
                throw new BadRequestException("Item " + line.itemId() + " does not belong to this handoff.");
            }
            ItemCondition condition = line.condition() == null ? ItemCondition.GOOD : line.condition();
            BigDecimal outgoing = item.getQuantity();
            BigDecimal returnedSoFar = returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO)
                    .add(pending.getOrDefault(item.getId(), BigDecimal.ZERO))
                    .add(returnedInThisRequest.getOrDefault(item.getId(), BigDecimal.ZERO));
            BigDecimal declaredSoFar = declaredMissing.getOrDefault(item.getId(), BigDecimal.ZERO)
                    .add(missingInThisRequest.getOrDefault(item.getId(), BigDecimal.ZERO));
            BigDecimal netMissing = ReturnQueryService.netMissing(outgoing, returnedSoFar, declaredSoFar);
            BigDecimal remaining = outgoing.subtract(returnedSoFar).subtract(netMissing);
            if (condition == ItemCondition.MISSING) {
                // Only units still outstanding (returns cover outstanding units first) can be
                // newly marked missing.
                if (line.quantity().compareTo(remaining) > 0) {
                    throw new ConflictException(
                            "Cannot mark more of '" + item.getName() + "' as missing than are outstanding ("
                                    + plain(remaining) + " outstanding).");
                }
                missingInThisRequest.merge(item.getId(), line.quantity(), BigDecimal::add);
            } else {
                // A return may clear an outstanding unit OR bring back a previously-missing
                // one, so it is capped only by everything not yet returned (remaining + missing).
                BigDecimal owed = remaining.add(netMissing);
                if (line.quantity().compareTo(owed) > 0) {
                    throw new ConflictException(
                            "Return quantity for '" + item.getName() + "' exceeds what is still owed ("
                                    + plain(owed) + " not yet returned).");
                }
                returnedInThisRequest.merge(item.getId(), line.quantity(), BigDecimal::add);
            }
            event.addLine(new ReturnLine(item, line.quantity(), condition, trimToNull(line.note())));
        }

        if (autoConfirm) {
            event.setConfirmed(true);
            event.setConfirmedAt(Instant.now());
            event.setConfirmedByRef(actorRef);
        }
        event = returnEventRepository.save(event);

        auditService.record(handoff.getId(), AuditEventType.RETURN_CREATED, actorType, actorRef,
                "Return recorded" + (autoConfirm ? " and confirmed." : " (awaiting confirmation)."));
        if (autoConfirm) {
            auditService.record(handoff.getId(), AuditEventType.RETURN_CONFIRMED, actorType, actorRef,
                    "Return confirmed.");
        }
        handoffService.recomputeReturnStatus(handoff);
        return ReturnEventResponse.from(event);
    }

    private static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
