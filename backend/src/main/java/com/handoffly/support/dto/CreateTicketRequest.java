package com.handoffly.support.dto;

import com.handoffly.support.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a customer fills in. Who they are (account ID, name, email) is never taken from the request —
 * it comes from the signed-in account. {@code phone} defaults to the account's phone when left out.
 */
public record CreateTicketRequest(
        @NotBlank @Size(max = 200) String subject,
        @NotNull TicketCategory category,
        @NotBlank @Size(max = 5000) String description,
        @Size(max = 20) String handoffReference,
        @Size(max = 40) String phone
) {}
