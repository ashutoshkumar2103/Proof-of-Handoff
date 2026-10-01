package com.handoffly.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReturnWaitRequest(
        @NotBlank @Size(max = 200) String acknowledgementName,
        @NotBlank @Size(max = 1000) String reason
) {}
