package com.handoffly.job;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.user.SubscriptionPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * The fact that a customer was reminded about the end of one subscription period. (customer, end date) is unique, so
 * the same period is never reminded twice; a renewal moves the end date and so allows the next reminder.
 */
@Entity
@Immutable
@Table(name = "subscription_expiry_reminder")
public class SubscriptionExpiryReminder extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "valid_until", nullable = false, updatable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SubscriptionPlan plan;

    protected SubscriptionExpiryReminder() {
        // JPA
    }

    public SubscriptionExpiryReminder(Long userId, Instant validUntil, SubscriptionPlan plan) {
        this.userId = userId;
        this.validUntil = validUntil;
        this.plan = plan;
    }

    public Long getUserId() { return userId; }
    public Instant getValidUntil() { return validUntil; }
    public SubscriptionPlan getPlan() { return plan; }
}
