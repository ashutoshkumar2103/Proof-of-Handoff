package com.handoffly.support;

/** The changes support staff make that are recorded: to a customer account, or to the support team itself. */
public enum SupportAuditEventType {
    PLAN_CHANGED,
    /** The plan stayed, how long it is paid for changed (previous and new value are the last day, or "none"). */
    SUBSCRIPTION_PERIOD_CHANGED,
    HANDOFF_PREFIX_CHANGED,
    STAFF_CREATED,
    STAFF_DEACTIVATED,
    STAFF_REACTIVATED,
    STAFF_ROLE_CHANGED
}
