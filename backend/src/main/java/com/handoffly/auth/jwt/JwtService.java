package com.handoffly.auth.jwt;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Issues and validates HS256 JWTs for the two separate identities: customers and support staff.
 * Every token is bound to its audience, and each {@code parse…} method accepts only its own, so a
 * customer token is worthless on the support API and a staff token is worthless on the customer API.
 * Claims carry only the subject id and email; what a caller may do is decided server-side.
 * <p>
 * A token also ends by INACTIVITY: it carries the moment its holder last reported being active ({@code act}), and is
 * refused once that is older than {@link #INACTIVITY_LIMIT} (plus the one-minute interval at which the apps report). Only
 * {@link #renewCustomer}/{@link #renewStaff}, called from the endpoints the apps use when a person really interacts,
 * move that moment forward; an ordinary request, a poll or a page load never does. A renewed token keeps the original
 * expiry, so the overall lifetime ({@code expiration-minutes}) is not extended.
 */
@Service
public class JwtService {

    /** The value application.yml falls back to when JWT_SECRET is not set: fine on a laptop, never in production. */
    private static final String DEVELOPMENT_SECRET_MARKER = "change-me-in-production";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TOKEN_VERSION = "tv";
    private static final String CLAIM_LAST_ACTIVITY = "act";
    private static final String CUSTOMER_AUDIENCE = "handoffly-customers";
    private static final String STAFF_AUDIENCE = "handoffly-support";
    private static final String BEARER_PREFIX = "Bearer ";
    /** How long a session may sit without its holder doing anything before the server stops accepting it. */
    public static final Duration INACTIVITY_LIMIT = Duration.ofMinutes(15);
    /** The apps tell the server about activity at most once a minute, so a person who was active within the limit is never refused early. */
    private static final Duration ACTIVITY_REPORT_INTERVAL = Duration.ofMinutes(1);

    private final SecretKey key;
    private final long customerExpirationMinutes;
    private final long staffExpirationMinutes;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwtService(HandOfflyProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** With a chosen clock, so that tests can age a session without waiting. */
    JwtService(HandOfflyProperties properties, Clock clock) {
        this.clock = clock;
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

    /**
     * The same session with its last activity moved to now. The expiry stays the original one.
     * @param authorization the request's {@code Authorization} header value ({@code Bearer <token>})
     * @return {@code null} when it does not hold a valid, still-active customer token
     */
    public IssuedToken renewCustomer(String authorization) {
        return renew(authorization, CUSTOMER_AUDIENCE);
    }

    /** @see #renewCustomer */
    public IssuedToken renewStaff(String authorization) {
        return renew(authorization, STAFF_AUDIENCE);
    }

    private IssuedToken renew(String authorization, String audience) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) return null;
        ParsedToken current = parse(authorization.substring(BEARER_PREFIX.length()), audience);
        return current == null ? null : sign(audience, current.id(), current.email(), current.expiresAt(), current.tokenVersion());
    }

    private IssuedToken issue(String audience, Long subjectId, String email, long expirationMinutes, int tokenVersion) {
        return sign(audience, subjectId, email, clock.instant().plus(expirationMinutes, ChronoUnit.MINUTES), tokenVersion);
    }

    private IssuedToken sign(String audience, Long subjectId, String email, Instant expiry, int tokenVersion) {
        Instant now = clock.instant();
        String token = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(String.valueOf(subjectId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(CLAIM_LAST_ACTIVITY, now.getEpochSecond())
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
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // A token from before versions existed has no claim and counts as version 0, the starting version.
            Integer version = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
            // A token from before activity was recorded counts as last active when it was issued.
            Number reported = claims.get(CLAIM_LAST_ACTIVITY, Number.class);
            Instant lastActivity = reported == null ? claims.getIssuedAt().toInstant() : Instant.ofEpochSecond(reported.longValue());
            if (Duration.between(lastActivity, clock.instant()).compareTo(INACTIVITY_LIMIT.plus(ACTIVITY_REPORT_INTERVAL)) > 0) {
                return null;
            }
            return new ParsedToken(Long.valueOf(claims.getSubject()), claims.get(CLAIM_EMAIL, String.class),
                    version == null ? 0 : version, claims.getExpiration().toInstant());
        } catch (Exception e) {
            // Invalid tokens are simply unauthenticated; never log the token itself.
            return null;
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    /** {@code tokenVersion} is only meaningful for customer tokens (see {@code User#getTokenVersion}). */
    public record ParsedToken(Long id, String email, int tokenVersion, Instant expiresAt) {}
}
