package com.handoffly.auth.jwt;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.user.Role;
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
 * Issues and validates HS256 JWTs. Claims carry the user id, email and role so
 * requests authenticate without a database round-trip.
 */
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expirationMinutes;
    private final String issuer;

    public JwtService(HandOfflyProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "handoffly.jwt.secret must be at least 32 bytes for HS256; set a strong JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMinutes = properties.getJwt().getExpirationMinutes();
        this.issuer = properties.getJwt().getIssuer();
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiry);
    }

    /**
     * @return parsed principal claims, or {@code null} if the token is missing,
     * malformed, expired or has an invalid signature.
     */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new ParsedToken(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
        } catch (Exception e) {
            // Invalid tokens are simply unauthenticated; never log the token itself.
            return null;
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public record ParsedToken(Long userId, String email, Role role) {}
}
