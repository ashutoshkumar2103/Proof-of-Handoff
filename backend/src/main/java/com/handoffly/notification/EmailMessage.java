package com.handoffly.notification;

import java.util.List;

/**
 * A provider-agnostic outbound email. {@code htmlBody} is optional; when null the
 * plain-text body is sent alone. {@code attachments} is never null (empty when none).
 */
public record EmailMessage(
        String to,
        String subject,
        String textBody,
        String htmlBody,
        List<Attachment> attachments
) {
    public EmailMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** A message without attachments. */
    public EmailMessage(String to, String subject, String textBody, String htmlBody) {
        this(to, subject, textBody, htmlBody, List.of());
    }

    public record Attachment(String filename, String contentType, byte[] content) {}
}
