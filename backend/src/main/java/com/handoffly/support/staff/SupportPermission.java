package com.handoffly.support.staff;

/**
 * Something a support staff member may do. Endpoints ask for a permission; {@link SupportRole} says
 * which roles hold it — the only place that mapping exists. Customers have no permissions here at all.
 */
public enum SupportPermission {
    /** The support dashboard's ticket metrics. */
    VIEW_DASHBOARD,
    /** Open, search, answer and change the status of tickets. */
    WORK_TICKETS,
    /** Search customers and read their profiles. */
    VIEW_CUSTOMERS,
    /** Change a customer's subscription plan and handoff prefix. */
    MANAGE_CUSTOMERS,
    /** List, create, (de)activate staff and change their roles. */
    MANAGE_STAFF,
    /** Read the full support audit trail. */
    VIEW_AUDIT
}
