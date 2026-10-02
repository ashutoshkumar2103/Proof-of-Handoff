package com.handoffly.support;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.support.staff.SupportStaff;
import com.handoffly.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * One change a support staff member (the actor) made — to a customer account, or to another staff member
 * (the target; exactly one of the two is set): what it was, what it became, who did it and why.
 * Append-only history — the entity is immutable, has no setters, and the repository offers no update or
 * delete, so nothing in the application (and no screen) can edit it.
 */
@Entity
@Immutable
@Table(name = "support_audit_event")
public class SupportAuditEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false, updatable = false)
    private SupportStaff staff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", updatable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_staff_id", updatable = false)
    private SupportStaff targetStaff;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private SupportAuditEventType type;

    /** Null when there was no previous value (e.g. a staff member being created). */
    @Column(name = "previous_value", updatable = false, length = 60)
    private String previousValue;

    @Column(name = "new_value", nullable = false, updatable = false, length = 60)
    private String newValue;

    @Column(updatable = false, length = 500)
    private String reason;

    protected SupportAuditEvent() {
        // JPA
    }

    private SupportAuditEvent(SupportStaff actor, User customer, SupportStaff targetStaff, SupportAuditEventType type,
                              String previousValue, String newValue, String reason) {
        this.staff = actor;
        this.customer = customer;
        this.targetStaff = targetStaff;
        this.type = type;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    /** A change an actor made to a customer account. */
    public static SupportAuditEvent ofCustomerChange(SupportStaff actor, User customer, SupportAuditEventType type,
                                                     String previousValue, String newValue, String reason) {
        return new SupportAuditEvent(actor, customer, null, type, previousValue, newValue, reason);
    }

    /** A change an actor made to a staff member. */
    public static SupportAuditEvent ofStaffChange(SupportStaff actor, SupportStaff target, SupportAuditEventType type,
                                                  String previousValue, String newValue, String reason) {
        return new SupportAuditEvent(actor, null, target, type, previousValue, newValue, reason);
    }

    /** The Account ID or Staff ID the change was made to. */
    public String subjectCode() {
        return customer != null ? customer.getAccountCode() : targetStaff.getStaffCode();
    }

    public SupportStaff getStaff() { return staff; }
    public SupportAuditEventType getType() { return type; }
    public String getPreviousValue() { return previousValue; }
    public String getNewValue() { return newValue; }
    public String getReason() { return reason; }
}
