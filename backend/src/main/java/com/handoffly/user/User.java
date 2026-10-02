package com.handoffly.user;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.common.util.PublicCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A customer account: the sender/owner of handoffs. (Support staff are a separate identity, never a
 * customer.) The customer-facing {@code accountCode} (e.g. CUS-42) is stable for life and is what support
 * and customers use to refer to an account; the numeric id stays internal. Recipients are NOT
 * users — they interact through opaque, per-handoff links without an account.
 */
@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

    /** Letters only, upper case, 2-5 long: safe in references, URLs and filenames. */
    public static final String HANDOFF_PREFIX_REGEX = "[A-Z]{2,5}";
    public static final String DEFAULT_HANDOFF_PREFIX = "HO";
    private static final Pattern HANDOFF_PREFIX = Pattern.compile(HANDOFF_PREFIX_REGEX);

    @Column(name = "account_code", nullable = false, unique = true, updatable = false, length = 20)
    private String accountCode;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(name = "organization", length = 200)
    private String organization;

    @Column(length = 40)
    private String phone;

    @Column(nullable = false)
    private boolean enabled = true;

    /** The customer's plan; null until one is paid for or put in place by support (see {@link #hasPlan()}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_plan", length = 20)
    private SubscriptionPlan subscriptionPlan;

    /** When the current plan began; null when there is no plan, and for accounts that predate this being recorded. */
    @Column(name = "plan_started_at")
    private Instant planStartedAt;

    /** Until when the current plan is paid for; null means it has no end date (and is null too while there is no plan). */
    @Column(name = "plan_valid_until")
    private Instant planValidUntil;

    /** Prefix for the references of NEW handoffs (e.g. AV gives AV-1, AV-2). Existing ones keep theirs. */
    @Column(name = "handoff_prefix", nullable = false, length = 5)
    private String handoffPrefix = DEFAULT_HANDOFF_PREFIX;

    /** The last handoff number issued to this account; never reset, independent of the prefix. */
    @Column(name = "handoff_sequence", nullable = false)
    private long handoffSequence;

    /**
     * Which generation of sign-in tokens is current. Every customer token carries the version it was issued
     * under; raising it (a password change or reset) makes all earlier tokens stop working at once.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    protected User() {
        // JPA
    }

    public User(String accountCode, String email, String passwordHash, String displayName,
                String organization, String phone) {
        this.accountCode = accountCode;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.organization = organization;
        this.phone = phone;
        // No plan yet: a new account has nothing active until a plan is paid for or support puts it on one.
    }

    /**
     * Whether any plan has ever been put on this account. A new account has none; once one is paid for or put in place
     * by support there always is one (it may later lapse, which is a different thing: see {@link #subscriptionStatus}).
     */
    public boolean hasPlan() {
        return subscriptionPlan != null;
    }

    /**
     * ACTIVE while there is a plan with no end date or an end date still ahead; INACTIVE when there is no plan at all, or
     * once its end date has passed. {@link #hasPlan()} tells those two apart.
     */
    public SubscriptionStatus subscriptionStatus(Instant now) {
        return hasPlan() && (planValidUntil == null || planValidUntil.isAfter(now))
                ? SubscriptionStatus.ACTIVE : SubscriptionStatus.INACTIVE;
    }

    /**
     * The plan whose benefits apply right now: the customer's plan while its subscription is ACTIVE, otherwise the
     * fallback (see {@link SubscriptionPlan#LAPSED_FALLBACK}) — also for an account with no plan, which gets none of the
     * extras. Everything that grants or hides a plan-dependent feature asks this, never the raw plan, so a lapsed
     * subscription is treated the same everywhere.
     */
    public SubscriptionPlan entitledPlan(Instant now) {
        return subscriptionStatus(now) == SubscriptionStatus.ACTIVE ? subscriptionPlan : SubscriptionPlan.LAPSED_FALLBACK;
    }

    public SubscriptionPlan entitledPlan() {
        return entitledPlan(Instant.now());
    }

    /** Puts the customer on a plan from {@code startsAt}, paid for until {@code validUntil} (null: no end date). */
    public void startPlan(SubscriptionPlan plan, Instant startsAt, Instant validUntil) {
        this.subscriptionPlan = plan;
        this.planStartedAt = startsAt;
        this.planValidUntil = validUntil;
    }

    /**
     * Issues this account's next handoff reference. The caller must hold a row lock on this user
     * (see {@code UserRepository#findByIdForUpdate}) so concurrent creations get distinct numbers.
     */
    public String nextHandoffReference() {
        handoffSequence++;
        return PublicCode.handoff(handoffPrefix, handoffSequence);
    }

    /** The reference the next handoff would get (for display only; does not consume a number). */
    public String peekNextHandoffReference() {
        return PublicCode.handoff(handoffPrefix, handoffSequence + 1);
    }

    public void changeHandoffPrefix(String prefix) {
        if (prefix == null || !HANDOFF_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("A handoff prefix is 2-5 upper-case letters.");
        }
        this.handoffPrefix = prefix;
    }

    public String getAccountCode() { return accountCode; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public SubscriptionPlan getSubscriptionPlan() { return subscriptionPlan; }
    public void setSubscriptionPlan(SubscriptionPlan subscriptionPlan) { this.subscriptionPlan = subscriptionPlan; }

    /** Sets a new password hash and signs out every other session (all earlier tokens stop working). */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.tokenVersion++;
    }

    public int getTokenVersion() { return tokenVersion; }
    public Instant getPlanStartedAt() { return planStartedAt; }
    public Instant getPlanValidUntil() { return planValidUntil; }
    public String getHandoffPrefix() { return handoffPrefix; }
    public long getHandoffSequence() { return handoffSequence; }
}
