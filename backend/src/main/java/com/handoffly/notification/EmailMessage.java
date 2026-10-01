package com.handoffly.notification;

/**
 * A provider-agnostic outbound email. {@code htmlBody} is optional; when null the
 * plain-text body is sent alone.
 */
public record EmailMessage(
        String to,
        String subject,
        String textBody,
        String htmlBody
) {}
