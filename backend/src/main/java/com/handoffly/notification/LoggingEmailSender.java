package com.handoffly.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development email transport: logs the message instead of sending it, so recipient
 * review links can be tested locally without a mail server and without accidentally
 * emailing real people. This is the default provider. It intentionally logs the body
 * (including the link) because surfacing that message is its entire purpose — it must
 * never be enabled in production, where {@link SmtpEmailSender} is used instead.
 */
@Component
@ConditionalOnProperty(name = "handoffly.mail.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("""

                ===== [DEV EMAIL — not actually sent] =====
                To:      {}
                Subject: {}
                ---------------------------------------------
                {}
                =============================================
                """, message.to(), message.subject(), message.textBody());
    }
}
