package com.handoffly.handoff;

import com.handoffly.common.error.ConflictException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The single authoritative definition of allowed handoff status transitions.
 * No other layer (controller, repository, frontend) may re-encode these rules as
 * authority. Callers ask {@link #assertCanTransition} before persisting a new status.
 */
@Component
public class HandoffStateMachine {

    private final Map<HandoffStatus, Set<HandoffStatus>> allowed = new EnumMap<>(HandoffStatus.class);

    public HandoffStateMachine() {
        allowed.put(HandoffStatus.DRAFT, Set.of(
                HandoffStatus.OUTGOING_SENT, HandoffStatus.CANCELLED));
        allowed.put(HandoffStatus.OUTGOING_SENT, Set.of(
                HandoffStatus.AWAITING_RECIPIENT, HandoffStatus.CANCELLED));
        allowed.put(HandoffStatus.AWAITING_RECIPIENT, Set.of(
                HandoffStatus.ACTIVE_WITH_RECIPIENT, HandoffStatus.REJECTED,
                HandoffStatus.CANCELLED, HandoffStatus.OVERDUE));
        allowed.put(HandoffStatus.ACTIVE_WITH_RECIPIENT, Set.of(
                HandoffStatus.RETURN_PENDING, HandoffStatus.PARTIALLY_RETURNED,
                HandoffStatus.FULLY_RETURNED, HandoffStatus.OVERDUE, HandoffStatus.DISPUTED));
        allowed.put(HandoffStatus.RETURN_PENDING, Set.of(
                HandoffStatus.PARTIALLY_RETURNED, HandoffStatus.FULLY_RETURNED,
                HandoffStatus.OVERDUE, HandoffStatus.DISPUTED));
        // PARTIALLY_RETURNED can be closed once everything is accounted and any missing
        // items have been confirmed (enforced in HandoffService.close).
        allowed.put(HandoffStatus.PARTIALLY_RETURNED, Set.of(
                HandoffStatus.PARTIALLY_RETURNED, HandoffStatus.FULLY_RETURNED,
                HandoffStatus.CLOSED, HandoffStatus.OVERDUE, HandoffStatus.DISPUTED));
        allowed.put(HandoffStatus.FULLY_RETURNED, Set.of(
                HandoffStatus.CLOSED, HandoffStatus.DISPUTED));
        // OVERDUE / DISPUTED can recover back into the return flow or close out.
        allowed.put(HandoffStatus.OVERDUE, Set.of(
                HandoffStatus.PARTIALLY_RETURNED, HandoffStatus.FULLY_RETURNED,
                HandoffStatus.CLOSED, HandoffStatus.DISPUTED, HandoffStatus.ACTIVE_WITH_RECIPIENT));
        allowed.put(HandoffStatus.DISPUTED, Set.of(
                HandoffStatus.ACTIVE_WITH_RECIPIENT, HandoffStatus.PARTIALLY_RETURNED,
                HandoffStatus.FULLY_RETURNED, HandoffStatus.CANCELLED, HandoffStatus.CLOSED));
        // Terminal
        allowed.put(HandoffStatus.CLOSED, Set.of());
        allowed.put(HandoffStatus.REJECTED, Set.of());
        allowed.put(HandoffStatus.CANCELLED, Set.of());
    }

    public boolean canTransition(HandoffStatus from, HandoffStatus to) {
        if (from == to) return false;
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertCanTransition(HandoffStatus from, HandoffStatus to) {
        if (!canTransition(from, to)) {
            throw new ConflictException(
                    "Illegal status transition: " + from + " -> " + to + ".");
        }
    }
}
