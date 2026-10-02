package com.handoffly.payment;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A confirmed payment for a plan, waiting to be applied to an account (or already applied). Only the SHA-256
 * hash of its one-time token is stored; whoever holds the token, while signed in, can apply the plan once,
 * before it expires. The row stays as the record of what was bought, by whom, and which plan it replaced.
 */
@Entity
@Table(name = "payment")
public class Payment extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SubscriptionPlan plan;

    /** What was paid, in whole currency units, as the plan's price was when it was paid. */
    @Column(nullable = false, updatable = false)
    private int amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redeemed_by_user_id")
    private User redeemedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_before", length = 20)
    private SubscriptionPlan planBefore;

    protected Payment() {
        // JPA
    }

    public Payment(PaymentProvider provider, SubscriptionPlan plan, String tokenHash, Instant paidAt, Instant expiresAt) {
        this.provider = provider;
        this.plan = plan;
        this.amount = plan.amount();
        this.currency = SubscriptionPlan.CURRENCY;
        this.tokenHash = tokenHash;
        this.paidAt = paidAt;
        this.expiresAt = expiresAt;
    }

    /** Whether it can still be applied: not used yet and not expired. */
    public boolean isRedeemable(Instant now) {
        return redeemedAt == null && expiresAt.isAfter(now);
    }

    /** Records that the plan was applied to this account, replacing {@code before}. */
    public void redeem(User by, SubscriptionPlan before, Instant now) {
        this.redeemedBy = by;
        this.planBefore = before;
        this.redeemedAt = now;
    }

    public SubscriptionPlan getPlan() { return plan; }
    public int getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRedeemedAt() { return redeemedAt; }
}
