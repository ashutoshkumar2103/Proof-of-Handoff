package com.handoffly.recipient;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.handoff.Handoff;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A secure, expiring, single-handoff access grant for an unauthenticated recipient.
 * Only the SHA-256 {@code tokenHash} is stored; the raw token exists solely in the
 * emailed link. A token grants access to exactly one handoff — never others.
 */
@Entity
@Table(name = "recipient_link")
public class RecipientLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handoff_id", nullable = false)
    private Handoff handoff;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(nullable = false)
    private boolean revoked;

    protected RecipientLink() {
        // JPA
    }

    public RecipientLink(Handoff handoff, String tokenHash, Instant expiresAt) {
        this.handoff = handoff;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }

    public Handoff getHandoff() { return handoff; }
    public String getTokenHash() { return tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
