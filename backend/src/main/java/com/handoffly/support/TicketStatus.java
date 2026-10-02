package com.handoffly.support;

import java.util.List;

/**
 * Where a support ticket stands. Deliberately small — this is a ticket list, not a helpdesk
 * workflow engine. The few rules that exist live here, in one place.
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_FOR_CUSTOMER,
    RESOLVED,
    CLOSED;

    private static final List<TicketStatus> ACTIVE = List.of(OPEN, IN_PROGRESS, WAITING_FOR_CUSTOMER);

    /** Statuses that still need someone's attention (everything except resolved / closed). */
    public static List<TicketStatus> active() {
        return ACTIVE;
    }

    /** A closed ticket is final: it takes no more replies and no more status changes. */
    public boolean isClosed() {
        return this == CLOSED;
    }

    /** The status after the customer replies: a ticket that was waiting on them, or already resolved, needs attention again. */
    public TicketStatus afterCustomerReply() {
        return this == WAITING_FOR_CUSTOMER || this == RESOLVED ? OPEN : this;
    }
}
