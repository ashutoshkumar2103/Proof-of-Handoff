package com.handoffly.support.staff;

import java.util.EnumSet;
import java.util.Set;

import static com.handoffly.support.staff.SupportPermission.MANAGE_CUSTOMERS;
import static com.handoffly.support.staff.SupportPermission.VIEW_CUSTOMERS;
import static com.handoffly.support.staff.SupportPermission.VIEW_DASHBOARD;
import static com.handoffly.support.staff.SupportPermission.WORK_TICKETS;

/**
 * What a support staff member is. These roles belong to support staff only — a customer account has no
 * role. Each role is a fixed set of permissions; the hierarchy is ADMIN ⊃ MANAGER ⊃ TICKET_AGENT.
 */
public enum SupportRole {
    /** Full support administration, and the only role that manages staff. */
    ADMIN(EnumSet.allOf(SupportPermission.class)),
    /** Customer, plan, prefix and ticket operations — no staff management. */
    MANAGER(EnumSet.of(VIEW_DASHBOARD, WORK_TICKETS, VIEW_CUSTOMERS, MANAGE_CUSTOMERS)),
    /** Tickets only — no customer administration. */
    TICKET_AGENT(EnumSet.of(VIEW_DASHBOARD, WORK_TICKETS));

    private final Set<SupportPermission> permissions;

    SupportRole(Set<SupportPermission> permissions) {
        this.permissions = permissions;
    }

    public Set<SupportPermission> permissions() {
        return permissions;
    }

    public boolean can(SupportPermission permission) {
        return permissions.contains(permission);
    }

    /** Whether staff management may hand this role out or take it away. Admins are never managed through the API. */
    public boolean isAssignable() {
        return this != ADMIN;
    }
}
