package com.handoffly.user;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.common.util.PublicCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_plan", nullable = false, length = 20)
    private SubscriptionPlan subscriptionPlan = SubscriptionPlan.MONTHLY;

    /** Prefix for the references of NEW handoffs (e.g. AV gives AV-1, AV-2). Existing ones keep theirs. */
    @Column(name = "handoff_prefix", nullable = false, length = 5)
    private String handoffPrefix = DEFAULT_HANDOFF_PREFIX;

    /** The last handoff number issued to this account; never reset, independent of the prefix. */
    @Column(name = "handoff_sequence", nullable = false)
    private long handoffSequence;

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

    public String getHandoffPrefix() { return handoffPrefix; }
    public long getHandoffSequence() { return handoffSequence; }
}
