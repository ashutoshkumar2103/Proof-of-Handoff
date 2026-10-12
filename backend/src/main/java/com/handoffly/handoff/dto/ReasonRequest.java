package com.handoffly.handoff.dto;

import jakarta.validation.constraints.Size;

/** Free-text reason for actions such as cancel or dispute (the service requires one for those two; for closing it is required only when items are missing). */
public record ReasonRequest(
        @Size(max = 1000) String reason
) {}
