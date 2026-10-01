package com.handoffly.handoff;

import java.util.Set;

/**
 * Lifecycle states of a handoff. The permitted transitions between them are defined
 * once, in {@link HandoffStateMachine} — this enum only classifies states.
 */
public enum HandoffStatus {
    DRAFT,
    OUTGOING_SENT,
    AWAITING_RECIPIENT,
    ACTIVE_WITH_RECIPIENT,
    RETURN_PENDING,
    PARTIALLY_RETURNED,
    FULLY_RETURNED,
    CLOSED,
    // Exceptional
    REJECTED,
    CANCELLED,
    DISPUTED,
    OVERDUE;

    private static final Set<HandoffStatus> TERMINAL = Set.of(CLOSED, REJECTED, CANCELLED);

    /** Editing of items/details is only allowed while the handoff is a draft. */
    public boolean isDraft() {
        return this == DRAFT;
    }

    /** No further transitions are allowed from a terminal state. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Recipient has accepted and items are (at least partly) out with them. */
    public boolean isActiveWithRecipient() {
        return this == ACTIVE_WITH_RECIPIENT
                || this == RETURN_PENDING
                || this == PARTIALLY_RETURNED
                || this == OVERDUE
                || this == DISPUTED;
    }
}
