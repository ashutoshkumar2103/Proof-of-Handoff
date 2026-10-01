package com.handoffly.notification;

/**
 * Transport abstraction for outbound email. Implementations are selected by the
 * {@code handoffly.mail.provider} property; business logic never depends on a
 * specific provider.
 */
public interface EmailSender {
    void send(EmailMessage message);
}
