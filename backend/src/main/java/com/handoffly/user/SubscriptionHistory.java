package com.handoffly.user;

import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One plan a customer was moved to, with when it began, until when it was paid for, and who or what did it.
 * Append-only history: the entity is immutable and its repository offers no update or delete, so the record of
 * what a customer had can only grow. {@code changedAt} (the creation time) is when the change was made.
 * The staff member is kept as a plain id, so this module does not depend on the support module.
 */
@Entity
@Immutable
@Table(name = "subscription_history")
public class SubscriptionHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Null when there was no earlier plan on record. */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_plan", updatable = false, length = 20)
    private SubscriptionPlan previousPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_plan", nullable = false, updatable = false, length = 20)
    private SubscriptionPlan newPlan;

    @Column(name = "starts_at", updatable = false)
    private Instant startsAt;

    /** Null when the plan had no end date. */
    @Column(name = "valid_until", updatable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SubscriptionChangeSource source;

    @Column(name = "staff_id", updatable = false)
    private Long staffId;

    @Column(updatable = false, length = 500)
    private String reason;

    protected SubscriptionHistory() {
        // JPA
    }

    public SubscriptionHistory(User user, SubscriptionPlan previousPlan, SubscriptionPlan newPlan, Instant startsAt,
                               Instant validUntil, SubscriptionChangeSource source, Long staffId, String reason) {
        this.user = user;
        this.previousPlan = previousPlan;
        this.newPlan = newPlan;
        this.startsAt = startsAt;
        this.validUntil = validUntil;
        this.source = source;
        this.staffId = staffId;
        this.reason = reason;
    }

    public SubscriptionPlan getPreviousPlan() { return previousPlan; }
    public SubscriptionPlan getNewPlan() { return newPlan; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getValidUntil() { return validUntil; }
    public SubscriptionChangeSource getSource() { return source; }
    public Long getStaffId() { return staffId; }
    public String getReason() { return reason; }
}
