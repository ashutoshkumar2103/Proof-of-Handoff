package com.handoffly.audit;

/** Lifecycle events recorded for a handoff. The current status says what is happening
 *  now; these events say what happened before. */
public enum AuditEventType {
    HANDOFF_CREATED,
    HANDOFF_UPDATED,
    OUTGOING_SUBMITTED,
    RECIPIENT_LINK_SENT,
    RECIPIENT_OPENED,
    RECIPIENT_ACCEPTED,
    RECIPIENT_REJECTED,
    RETURN_CREATED,
    RETURN_UPDATED,
    RETURN_CONFIRMED,
    MISSING_CONFIRMATION_REQUESTED,
    MISSING_CONFIRMED,
    RETURN_WAIT_REQUESTED,
    HANDOFF_DISPUTED,
    HANDOFF_OVERDUE,
    ATTACHMENT_ADDED,
    ATTACHMENT_REMOVED,
    HANDOFF_CLOSED,
    HANDOFF_CANCELLED
}
