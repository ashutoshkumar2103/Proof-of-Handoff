package com.handoffly.recipient;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.ApiException;
import com.handoffly.common.util.SecureTokens;
import com.handoffly.handoff.Handoff;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Issues and resolves recipient access tokens. Raw tokens are generated here, hashed
 * for storage, and returned once to the caller (which emails them). Resolution enforces
 * expiry and revocation and scopes strictly to the token's own handoff.
 */
@Service
public class RecipientLinkService {

    /** 32 bytes = 256 bits of entropy — infeasible to guess. */
    private static final int TOKEN_BYTES = 32;

    private final RecipientLinkRepository repository;
    private final int ttlDays;

    public RecipientLinkService(RecipientLinkRepository repository, HandOfflyProperties properties) {
        this.repository = repository;
        this.ttlDays = properties.getRecipient().getTokenTtlDays();
    }

    /**
     * Revokes any existing links for the handoff and issues a fresh one.
     * @return the raw token to embed in the emailed link (never stored).
     */
    @Transactional
    public String issue(Handoff handoff) {
        repository.revokeAllForHandoff(handoff.getId());
        String rawToken = SecureTokens.randomToken(TOKEN_BYTES);
        String tokenHash = SecureTokens.sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        repository.save(new RecipientLink(handoff, tokenHash, expiresAt));
        return rawToken;
    }

    /**
     * Resolves a raw token to its usable link, or fails with a safe error.
     * @throws ApiException 404 if unknown, 410 GONE if revoked/expired.
     */
    @Transactional(readOnly = true)
    public RecipientLink resolveUsable(String rawToken) {
        RecipientLink link = repository.findByTokenHash(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "invalid_link",
                        "This link is not valid."));
        if (!link.isUsable(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "link_expired",
                    "This link has expired or is no longer active.");
        }
        return link;
    }

    /** Records the first time the recipient opened the link. */
    @Transactional
    public boolean markOpenedIfFirst(RecipientLink link) {
        if (link.getOpenedAt() == null) {
            link.setOpenedAt(Instant.now());
            repository.save(link);
            return true;
        }
        return false;
    }
}
