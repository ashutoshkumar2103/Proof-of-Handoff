package com.handoffly.notification;

import com.handoffly.common.config.HandOfflyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds and dispatches application notifications. The recipient review link is
 * constructed here from the configured base URL and the raw (unhashed) token, which
 * is the only place the raw token ever leaves the system.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String BUILT_IN_PDF_TEMPLATE = "mail/proof-of-handoff-email.txt";
    private static final String DEFAULT_PDF_SUBJECT = "Proof of Handoff — {handoffCode}";

    private final EmailSender emailSender;
    private final String linkBaseUrl;
    private final String pdfTemplateFile;
    private final EmailTemplate builtInPdfTemplate = loadBuiltInPdfTemplate();

    public NotificationService(EmailSender emailSender, HandOfflyProperties properties) {
        this.emailSender = emailSender;
        this.linkBaseUrl = stripTrailingSlash(properties.getRecipient().getLinkBaseUrl());
        this.pdfTemplateFile = properties.getMail().getPdfTemplateFile() == null
                ? "" : properties.getMail().getPdfTemplateFile().trim();
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

    /**
     * Emails the Proof-of-Handoff PDF as an attachment, worded by the reusable email template (see
     * {@code mail/proof-of-handoff-email.txt}). Only ever called on an explicit user action.
     * @return whether a real email went out (false when the development sender only logged it)
     */
    public boolean sendHandoffPdf(String toEmail, String recipientName, String senderName, String publicCode,
                                  String handoffTitle, String filename, byte[] pdf) {
        EmailTemplate.Rendered mail = pdfTemplate().render(Map.of(
                "recipientName", safe(recipientName),
                "senderName", orEmpty(senderName),
                "handoffCode", orEmpty(publicCode),
                "handoffTitle", orEmpty(handoffTitle),
                "attachmentName", orEmpty(filename),
                // Alternative names, so a template written with "quotation" wording works unchanged.
                "quotationCode", orEmpty(publicCode),
                "quotationTitle", orEmpty(handoffTitle)));

        emailSender.send(new EmailMessage(toEmail, mail.subject(), mail.body(), null,
                List.of(new EmailMessage.Attachment(filename, PDF_CONTENT_TYPE, pdf))));
        return emailSender.deliversMail();
    }

    /** The configured template file if there is one (read fresh each time, so edits apply at once), else the built-in. */
    private EmailTemplate pdfTemplate() {
        if (!pdfTemplateFile.isBlank()) {
            try {
                return EmailTemplate.parse(Files.readString(Path.of(pdfTemplateFile), StandardCharsets.UTF_8), DEFAULT_PDF_SUBJECT);
            } catch (IOException | RuntimeException e) {
                log.warn("Email template '{}' could not be read ({}); using the built-in template instead.",
                        pdfTemplateFile, e.getMessage());
            }
        }
        return builtInPdfTemplate;
    }

    private static EmailTemplate loadBuiltInPdfTemplate() {
        try (InputStream in = new ClassPathResource(BUILT_IN_PDF_TEMPLATE).getInputStream()) {
            return EmailTemplate.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), DEFAULT_PDF_SUBJECT);
        } catch (IOException e) {
            throw new IllegalStateException("Built-in email template " + BUILT_IN_PDF_TEMPLATE + " is missing.", e);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "there" : s;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
