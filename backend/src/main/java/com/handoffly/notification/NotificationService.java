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
    private static final String BUILT_IN_PASSWORD_RESET_TEMPLATE = "mail/password-reset-email.txt";
    private static final String DEFAULT_PASSWORD_RESET_SUBJECT = "Reset your HandOffly password";
    private static final String BUILT_IN_JOB_TEMPLATE = "mail/customer-job-email.txt";
    private static final String DEFAULT_JOB_SUBJECT = "{heading}";

    private final EmailSender emailSender;
    private final String linkBaseUrl;
    private final String pdfTemplateFile;
    private final EmailTemplate builtInPdfTemplate = loadBuiltIn(BUILT_IN_PDF_TEMPLATE, DEFAULT_PDF_SUBJECT);
    private final EmailTemplate passwordResetTemplate =
            loadBuiltIn(BUILT_IN_PASSWORD_RESET_TEMPLATE, DEFAULT_PASSWORD_RESET_SUBJECT);
    private final EmailTemplate customerJobTemplate = loadBuiltIn(BUILT_IN_JOB_TEMPLATE, DEFAULT_JOB_SUBJECT);

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

    /**
     * Emails a customer the one-time link for choosing a new password, worded by the reusable email template
     * (see {@code mail/password-reset-email.txt}). The raw token only ever leaves the system inside this link.
     */
    public void sendPasswordReset(String toEmail, String recipientName, String accountCode, String resetUrl,
                                  int expiresInMinutes) {
        EmailTemplate.Rendered mail = passwordResetTemplate.render(Map.of(
                "recipientName", safe(recipientName),
                "accountCode", orEmpty(accountCode),
                "resetUrl", resetUrl,
                "expiresInMinutes", String.valueOf(expiresInMinutes)));
        emailSender.send(new EmailMessage(toEmail, mail.subject(), mail.body(), null));
    }

    /**
     * Sends a customer ONE email for one run of one job — a reminder or the weekly summary — worded by the reusable
     * template (see {@code mail/customer-job-email.txt}). The heading is bold in the HTML version and capitalised in
     * the plain one, so the purpose is clear at a glance. It only carries what the customer's own handoffs say.
     */
    public void sendCustomerJob(String toEmail, String recipientName, String accountCode, CustomerJobEmail email) {
        String details = email.lines().stream().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
        EmailTemplate.Rendered mail = customerJobTemplate.render(Map.of(
                "heading", email.heading(),
                "headingUpper", email.heading().toUpperCase(java.util.Locale.ROOT),
                "headingRule", "=".repeat(Math.min(60, email.heading().length())),
                "recipientName", safe(recipientName),
                "accountCode", orEmpty(accountCode),
                "intro", email.intro(),
                "lines", details,
                "footnote", orEmpty(email.footnote())));
        emailSender.send(new EmailMessage(toEmail, mail.subject(), mail.body(), jobHtml(email, safe(recipientName))));
    }

    /** The same content as HTML, with the heading in bold. Everything that came from data is escaped. */
    private static String jobHtml(CustomerJobEmail email, String recipientName) {
        StringBuilder html = new StringBuilder("<div style=\"font-family:Arial,Helvetica,sans-serif;color:#1a2027;font-size:14px\">");
        html.append("<h1 style=\"font-size:22px;font-weight:bold;margin:0 0 16px\">").append(escape(email.heading())).append("</h1>");
        html.append("<p>Hello ").append(escape(recipientName)).append(",</p>");
        html.append("<p>").append(escape(email.intro())).append("</p><ul>");
        for (String line : email.lines()) {
            html.append("<li style=\"margin:4px 0\">").append(escape(line)).append("</li>");
        }
        html.append("</ul>");
        if (email.footnote() != null && !email.footnote().isBlank()) {
            html.append("<p style=\"color:#6b7280\">").append(escape(email.footnote())).append("</p>");
        }
        return html.append("<p>Thank You,<br>HandOffly</p></div>").toString();
    }

    private static String escape(String text) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(text == null ? "" : text);
    }

    /** Tells the support mailbox that a customer opened a ticket or sent a support message. */
    public void sendTicketCreated(String supportMailbox, TicketNotice ticket, String description) {
        String body = """
                A customer contacted support.

                Ticket:    %s
                Via:       %s
                Category:  %s
                Customer:  %s (%s)
                Email:     %s
                Phone:     %s
                Plan:      %s (%s priority)
                Handoff:   %s

                %s

                Open the ticket in the HandOffly support portal to reply.
                """.formatted(
                ticket.ticketCode(), ticket.contactMethod(), ticket.category(), ticket.customerName(),
                ticket.accountCode(), ticket.customerEmail(), orDash(ticket.customerPhone()), ticket.plan(),
                ticket.priority(), orDash(ticket.handoffReference()), description);
        emailSender.send(new EmailMessage(supportMailbox, ticketSubject(ticket, "New ticket"), body, null));
    }

    /** Tells the support mailbox that the customer replied on a ticket. */
    public void sendTicketCustomerReply(String supportMailbox, TicketNotice ticket, String reply) {
        String body = """
                %s (%s) replied on ticket %s:

                %s

                Open the ticket in the HandOffly support portal to respond.
                """.formatted(ticket.customerName(), ticket.accountCode(), ticket.ticketCode(), reply);
        emailSender.send(new EmailMessage(supportMailbox, ticketSubject(ticket, "Customer reply"), body, null));
    }

    /** Tells the customer that support replied on their ticket (or to their message). */
    public void sendTicketSupportReply(TicketNotice ticket, String reply) {
        String followUp = ticket.customerCanReply()
                ? "To answer, sign in to HandOffly, open Contact Support and choose this ticket."
                : "To follow up, sign in to HandOffly, open Contact Support and send us another message.";
        String body = """
                Hello %s,

                Our support team replied to your request %s:

                %s

                %s

                — HandOffly Support
                """.formatted(safe(ticket.customerName()), ticket.ticketCode(), reply, followUp);
        emailSender.send(new EmailMessage(ticket.customerEmail(), ticketSubject(ticket, "Reply from support"), body, null));
    }

    private static String ticketSubject(TicketNotice ticket, String what) {
        return "[" + ticket.ticketCode() + "] " + what + ": " + ticket.subject();
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

    /** A template shipped inside the application (under {@code mail/}). */
    private static EmailTemplate loadBuiltIn(String resource, String defaultSubject) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return EmailTemplate.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), defaultSubject);
        } catch (IOException e) {
            throw new IllegalStateException("Built-in email template " + resource + " is missing.", e);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "there" : s;
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
