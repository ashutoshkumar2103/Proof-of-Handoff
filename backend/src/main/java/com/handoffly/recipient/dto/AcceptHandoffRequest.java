package com.handoffly.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A recipient typed acknowledgement — used both on accept and when confirming missing
 * items. The typed name is a "Typed acknowledgement" / "Recipient acknowledgement" —
 * NOT a legally binding e-signature.
 */
public record AcceptHandoffRequest(
        @NotBlank @Size(max = 200) String acknowledgementName
) {}
