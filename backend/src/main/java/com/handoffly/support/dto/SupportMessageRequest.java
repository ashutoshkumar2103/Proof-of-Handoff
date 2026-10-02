package com.handoffly.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A support message from the customer app (sent as a form so a file can travel with it). Who is writing
 * (account ID, name, email, phone) is never taken from the request — it comes from the signed-in account.
 */
public record SupportMessageRequest(
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 5000) String message,
        @Size(max = 20) String handoffReference
) {}
