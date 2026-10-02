package com.handoffly.auth.jwt;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Issues and validates HS256 JWTs for the two separate identities: customers and support staff.
 * Every token is bound to its audience, and each {@code parse…} method accepts only its own, so a
 * customer token is worthless on the support API and a staff token is worthless on the customer API.
 * Claims carry only the subject id and email; what a caller may do is decided server-side.
 */
@Service
public class JwtService {

    /** The value application.yml falls back to when JWT_SECRET is not set: fine on a laptop, never in production. */
    private static final String DEVELOPMENT_SECRET_MARKER = "change-me-in-production";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TOKEN_VERSION = "tv";
    private static final String CUSTOMER_AUDIENCE = "handoffly-customers";
    private static final String STAFF_AUDIENCE = "handoffly-support";

    private final SecretKey key;
    private final long customerExpirationMinutes;
    private final long staffExpirationMinutes;
    private final String issuer;

    public JwtService(HandOfflyProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "handoffly.jwt.secret must be at least 32 bytes for HS256; set a strong JWT_SECRET.");
        }
        if (properties.getJwt().getSecret().startsWith(DEVELOPMENT_SECRET_MARKER)) {
            log.warn("The built-in development JWT secret is in use: anyone who knows it can forge sign-in tokens. "
                    + "Set a strong, private JWT_SECRET before running anywhere real.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.customerExpirationMinutes = properties.getJwt().getExpirationMinutes();
        this.staffExpirationMinutes = properties.getJwt().getStaffExpirationMinutes();
        this.issuer = properties.getJwt().getIssuer();
    }

    public IssuedToken issueForCustomer(User user) {
        return issue(CUSTOMER_AUDIENCE, user.getId(), user.getEmail(), customerExpirationMinutes, user.getTokenVersion());
    }

    public IssuedToken issueForStaff(Long staffId, String email) {
        return issue(STAFF_AUDIENCE, staffId, email, staffExpirationMinutes, 0);
    }

    /** @return the customer the token was issued to, or {@code null} for anything that is not a valid customer token. */
    public ParsedToken parseCustomer(String token) {
        return parse(token, CUSTOMER_AUDIENCE);
    }

    /** @return the staff member the token was issued to, or {@code null} for anything that is not a valid staff token. */
    public ParsedToken parseStaff(String token) {
        return parse(token, STAFF_AUDIENCE);
    }

    private IssuedToken issue(String audience, Long subjectId, String email, long expirationMinutes, int tokenVersion) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(String.valueOf(subjectId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiry);
    }

    private ParsedToken parse(String token, String audience) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // A token from before versions existed has no claim and counts as version 0, the starting version.
            Integer version = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
            return new ParsedToken(Long.valueOf(claims.getSubject()), claims.get(CLAIM_EMAIL, String.class),
                    version == null ? 0 : version);
        } catch (Exception e) {
            // Invalid tokens are simply unauthenticated; never log the token itself.
            return null;
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    /** {@code tokenVersion} is only meaningful for customer tokens (see {@code User#getTokenVersion}). */
    public record ParsedToken(Long id, String email, int tokenVersion) {}
}
