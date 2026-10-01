package com.handoffly.notification;

import com.handoffly.common.config.HandOfflyProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds real MIME messages with the production sender but captures them instead of opening an
 * SMTP connection, so nothing is ever sent.
 */
class SmtpEmailSenderTest {

    private static final class CapturingMailSender extends JavaMailSenderImpl {
        MimeMessage sent;

        @Override
        public void send(MimeMessage mimeMessage) {
            this.sent = mimeMessage;
        }
    }

    private static void collectAttachments(Part part, List<Part> found) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collectAttachments(child, found);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            found.add(part);
        }
    }

    @Test
    void sendsThePdfAsARealMimeAttachment() throws Exception {
        CapturingMailSender mail = new CapturingMailSender();
        SmtpEmailSender sender = new SmtpEmailSender(mail, new HandOfflyProperties());
        byte[] pdf = ("%PDF-1.7\n" + "x".repeat(2000)).getBytes();

        sender.send(new EmailMessage("client@example.com", "Proof of Handoff — HO-1042",
                "Attached is the Proof-of-Handoff record for Handoff HO-1042.", null,
                List.of(new EmailMessage.Attachment("HandOffly-HO-1042-Proof-of-Handoff.pdf", "application/pdf", pdf))));

        MimeMessage message = mail.sent;
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Proof of Handoff — HO-1042");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("client@example.com");
        assertThat(message.isMimeType("multipart/*")).isTrue();

        List<Part> attachments = new ArrayList<>();
        collectAttachments(message, attachments);
        assertThat(attachments).hasSize(1);
        Part attachment = attachments.getFirst();
        assertThat(attachment.getFileName()).isEqualTo("HandOffly-HO-1042-Proof-of-Handoff.pdf");
        assertThat(attachment.getContentType()).startsWith("application/pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(pdf);
    }

    @Test
    void onlyTheSmtpSenderClaimsToDeliverRealMail() {
        // The development sender just logs, so callers (and the UI) must never say "emailed" for it.
        assertThat(new LoggingEmailSender().deliversMail()).isFalse();
        assertThat(new SmtpEmailSender(new CapturingMailSender(), new HandOfflyProperties()).deliversMail()).isTrue();
    }

    @Test
    void aMessageWithoutAttachmentsOrHtmlStaysPlainText() throws Exception {
        CapturingMailSender mail = new CapturingMailSender();
        new SmtpEmailSender(mail, new HandOfflyProperties())
                .send(new EmailMessage("a@example.com", "Hello", "Plain body", null));

        MimeMessage message = mail.sent;
        message.saveChanges();
        assertThat(message.isMimeType("text/plain")).isTrue();
        assertThat(readBody(message)).contains("Plain body");
    }

    private static String readBody(MimeMessage message) throws IOException, jakarta.mail.MessagingException {
        return String.valueOf(message.getContent());
    }
}
