package com.handoffly.user;

/**
 * How urgently support attends to a customer, decided by their plan alone. It is a ranking for the
 * support desk, not a promised response time — HandOffly makes no SLA claim.
 */
public enum SupportPriority {
    NORMAL,
    PRIORITY,
    HIGHEST;

    /** Whether the support desk should treat these customers ahead of normal ones. */
    public boolean isElevated() {
        return this != NORMAL;
    }
}
