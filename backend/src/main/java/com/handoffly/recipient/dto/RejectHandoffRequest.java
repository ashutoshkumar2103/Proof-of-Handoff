package com.handoffly.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectHandoffRequest(
        @NotBlank @Size(max = 200) String acknowledgementName,
        // A reason is required when declining (it is not asked for when accepting).
        @NotBlank @Size(max = 1000) String reason
) {}
