package com.handoffly.notification;

import com.handoffly.common.config.HandOfflyProperties;
import org.springframework.stereotype.Service;

/**
 * Builds and dispatches application notifications. The recipient review link is
 * constructed here from the configured base URL and the raw (unhashed) token, which
 * is the only place the raw token ever leaves the system.
 */
@Service
public class NotificationService {

    private final EmailSender emailSender;
    private final String linkBaseUrl;

    public NotificationService(EmailSender emailSender, HandOfflyProperties properties) {
        this.emailSender = emailSender;
        this.linkBaseUrl = stripTrailingSlash(properties.getRecipient().getLinkBaseUrl());
    }

    /**
     * Emails the recipient a secure link to review and acknowledge a handoff.
     * @return the review URL that was sent (useful for tests/logging by callers).
     */
    public String sendRecipientReviewLink(String toEmail, String recipientName, String senderName,
                                          String handoffTitle, String publicCode, String rawToken) {
        String reviewUrl = linkBaseUrl + "/" + rawToken;
        String subject = "Please review handoff " + publicCode + ": " + handoffTitle;
        String body = """
                Hello %s,

                %s has sent you a handoff to review and acknowledge on HandOffly.

                Handoff: %s (%s)

                Open the secure link below to see what is being handed over and to
                accept or decline it:

                %s

                This link is unique to you and will expire. If you did not expect this,
                you can ignore this email.

                — HandOffly
                """.formatted(
                safe(recipientName), safe(senderName), handoffTitle, publicCode, reviewUrl);

        emailSender.send(new EmailMessage(toEmail, subject, body, null));
        return reviewUrl;
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "there" : s;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
