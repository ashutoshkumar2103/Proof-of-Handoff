package com.handoffly.notification;

import com.handoffly.common.config.HandOfflyProperties;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Production email transport over SMTP (Spring Mail). Never logs message bodies or
 * tokens. Enabled only when {@code handoffly.mail.provider=smtp}.
 */
@Component
@ConditionalOnProperty(name = "handoffly.mail.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, HandOfflyProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.getMail().getFrom();
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            boolean html = message.htmlBody() != null && !message.htmlBody().isBlank();
            boolean multipart = html || !message.attachments().isEmpty();
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (html) {
                helper.setText(message.textBody(), message.htmlBody());
            } else {
                helper.setText(message.textBody(), false);
            }
            for (EmailMessage.Attachment attachment : message.attachments()) {
                helper.addAttachment(attachment.filename(),
                        new ByteArrayResource(attachment.content()), attachment.contentType());
            }
            mailSender.send(mime);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send email to " + message.to(), e);
        }
    }
}
