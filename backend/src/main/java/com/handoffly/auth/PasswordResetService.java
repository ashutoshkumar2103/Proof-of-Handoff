package com.handoffly.auth;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.util.SecureTokens;
import com.handoffly.common.web.RateLimiter;
import com.handoffly.notification.NotificationService;
import com.handoffly.user.User;
import com.handoffly.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * "Forgot password": a one-time, expiring link by email that lets a customer choose a new password without
 * knowing the old one. Asking for a link never reveals whether an account exists — the answer is the same
 * either way, the email goes out in the background so the time taken tells nothing either, and asking is
 * limited per address and per email. Using a link raises the account's token version, which signs out every
 * existing session, and retires every other link for the account.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32;
    private static final Duration FORGOT_WINDOW = Duration.ofHours(1);
    private static final Executor BACKGROUND = Executors.newVirtualThreadPerTaskExecutor();

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final HandOfflyProperties properties;

    public PasswordResetService(PasswordResetTokenRepository tokens, UserRepository users,
                                PasswordEncoder passwordEncoder, NotificationService notifications,
                                RateLimiter limiter, HandOfflyProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * Emails a reset link if (and only if) an enabled account has this email; says nothing either way.
     * @throws com.handoffly.common.error.RateLimitedException if this email was asked for too often
     */
    @Transactional
    public void requestReset(String rawEmail) {
        String email = rawEmail.trim();
        limiter.hit("forgot-email:" + email.toLowerCase(Locale.ROOT),
                properties.getRateLimit().getForgotPerEmail(), FORGOT_WINDOW);

        users.findByEmailIgnoreCase(email).filter(User::isEnabled).ifPresent(user -> {
            Instant now = Instant.now();
            // Only the newest link works: asking again retires the earlier ones.
            tokens.findUsableByUserId(user.getId(), now).forEach(t -> t.markUsed(now));

            String rawToken = SecureTokens.randomToken(TOKEN_BYTES);
            int ttlMinutes = properties.getAuth().getResetTokenTtlMinutes();
            tokens.save(new PasswordResetToken(user, SecureTokens.sha256Hex(rawToken),
                    now.plus(Duration.ofMinutes(ttlMinutes))));

            String url = stripTrailingSlash(properties.getAuth().getResetLinkBaseUrl()) + "/" + rawToken;
            String to = user.getEmail();
            String name = user.getDisplayName();
            String accountCode = user.getAccountCode();
            afterCommit(() -> notifications.sendPasswordReset(to, name, accountCode, url, ttlMinutes));
        });
    }

    /**
     * Sets a new password from a reset link, once. An unknown, expired or already-used link is refused with
     * one message, so a link cannot be probed.
     */
    @Transactional
    public void reset(String rawToken, String newPassword, String confirmation) {
        PasswordPolicy.require(newPassword, confirmation);
        Instant now = Instant.now();
        PasswordResetToken token = tokens.findByTokenHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .filter(t -> t.isUsable(now) && t.getUser().isEnabled())
                .orElseThrow(() -> new BadRequestException(
                        "This reset link is invalid or has expired. Please request a new one."));

        User user = token.getUser();
        user.changePassword(passwordEncoder.encode(newPassword));   // also signs out every existing session
        token.markUsed(now);
        tokens.findUsableByUserId(user.getId(), now).forEach(t -> t.markUsed(now));
    }

    /** Runs after the change is committed and off the request thread, so neither a failure nor the delay shows. */
    private void afterCommit(Runnable send) {
        Runnable guarded = () -> {
            try {
                send.run();
            } catch (RuntimeException e) {
                log.warn("A password reset email could not be sent: {}", e.getMessage());
            }
        };
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (properties.getMail().isAsync()) {
                    BACKGROUND.execute(guarded);
                } else {
                    guarded.run();
                }
            }
        });
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
