package com.handoffly.handoff;

import com.handoffly.handoff.dto.HandoffItemResponse;
import com.handoffly.handoff.dto.HandoffSummaryResponse;
import com.handoffly.returns.ReturnQueryService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles handoff DTOs and performs the one canonical remaining-quantity computation
 * (outgoing − confirmed returned). Kept in one place so the arithmetic never diverges
 * between the list view, detail view and status derivation.
 */
@Component
public class HandoffMapper {

    /** Statuses for which a past due date makes the handoff overdue. */
    private static final Set<HandoffStatus> OVERDUE_ELIGIBLE = Set.of(
            HandoffStatus.ACTIVE_WITH_RECIPIENT,
            HandoffStatus.RETURN_PENDING,
            HandoffStatus.PARTIALLY_RETURNED,
            HandoffStatus.OVERDUE);

    /** Derived overdue flag: past its due date while items are still out with the recipient. */
    public boolean isOverdue(Handoff handoff) {
        return handoff.getDueAt() != null
                && handoff.getDueAt().isBefore(Instant.now())
                && OVERDUE_ELIGIBLE.contains(handoff.getStatus());
    }

    /**
     * @param returnedGood itemId -> quantity returned (all conditions except MISSING) in
     *                     confirmed returns. Returning a previously-missing unit lands here.
     * @param pending      itemId -> total in unconfirmed returns.
     * @param declaredMissing itemId -> quantity reported MISSING in confirmed returns (raw).
     * Net missing is the declared missing capped by what is not yet returned, so a return
     * that recovers a missing unit automatically reduces it:
     * outgoing = returnedConfirmed + missing + remaining (no double counting).
     */
    public List<HandoffItemResponse> itemResponses(Handoff handoff,
                                                   Map<Long, BigDecimal> returnedGood,
                                                   Map<Long, BigDecimal> pending,
                                                   Map<Long, BigDecimal> declaredMissing) {
        return handoff.getItems().stream()
                .map(item -> {
                    BigDecimal outgoing = item.getQuantity();
                    BigDecimal returned = returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO);
                    BigDecimal miss = ReturnQueryService.netMissing(outgoing, returned,
                            declaredMissing.getOrDefault(item.getId(), BigDecimal.ZERO));
                    BigDecimal returnedPending = pending.getOrDefault(item.getId(), BigDecimal.ZERO);
                    BigDecimal remaining = outgoing.subtract(returned).subtract(miss);
                    return new HandoffItemResponse(
                            item.getId(),
                            item.getName(),
                            item.getDescription(),
                            item.getSku(),
                            item.getSerialNumber(),
                            item.getAssetNumber(),
                            item.getUnit(),
                            item.getCondition(),
                            item.getNotes(),
                            outgoing,
                            returned,
                            miss,
                            returnedPending,
                            remaining);
                })
                .toList();
    }

    public HandoffSummaryResponse toSummary(Handoff handoff, Map<Long, BigDecimal> returnedGood,
                                            Map<Long, BigDecimal> declaredMissing) {
        BigDecimal totalOutgoing = BigDecimal.ZERO;
        BigDecimal totalReturned = BigDecimal.ZERO;  // good only
        BigDecimal totalMissing = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (HandoffItem item : handoff.getItems()) {
            BigDecimal outgoing = item.getQuantity();
            BigDecimal returned = returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal miss = ReturnQueryService.netMissing(outgoing, returned,
                    declaredMissing.getOrDefault(item.getId(), BigDecimal.ZERO));
            totalOutgoing = totalOutgoing.add(outgoing);
            totalReturned = totalReturned.add(returned);
            totalMissing = totalMissing.add(miss);
            totalRemaining = totalRemaining.add(outgoing.subtract(returned).subtract(miss));
        }
        return new HandoffSummaryResponse(
                handoff.getId(),
                handoff.getPublicCode(),
                handoff.getTitle(),
                handoff.getCategory(),
                handoff.getStatus(),
                handoff.getSenderName(),
                handoff.getRecipientName(),
                handoff.getRecipientEmail(),
                handoff.getItems().size(),
                totalOutgoing,
                totalReturned,
                totalMissing,
                totalRemaining,
                isOverdue(handoff),
                handoff.getOutgoingAt(),
                handoff.getDueAt(),
                handoff.getCreatedAt(),
                handoff.getUpdatedAt());
    }
}
