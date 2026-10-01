package com.handoffly.support;

import com.handoffly.notification.EmailMessage;
import com.handoffly.notification.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test email transport that captures the last message so tests can extract the recipient
 * review token from the emailed link. Registered as {@code @Primary} to override the
 * default logging sender.
 */
public class CapturingEmailSender implements EmailSender {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("/r/([A-Za-z0-9_-]+)");

    private volatile EmailMessage lastMessage;

    @Override
    public void send(EmailMessage message) {
        this.lastMessage = message;
    }

    public EmailMessage getLastMessage() {
        return lastMessage;
    }

    /** Extracts the recipient token from the most recent email's review link. */
    public String extractLastToken() {
        if (lastMessage == null) {
            throw new IllegalStateException("No email captured yet.");
        }
        Matcher m = TOKEN_IN_LINK.matcher(lastMessage.textBody());
        if (!m.find()) {
            throw new IllegalStateException("No review link found in email body.");
        }
        return m.group(1);
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }
}
