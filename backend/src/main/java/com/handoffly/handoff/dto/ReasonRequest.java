package com.handoffly.handoff.dto;

import jakarta.validation.constraints.Size;

/** Optional free-text reason for actions such as cancel or dispute. */
public record ReasonRequest(
        @Size(max = 1000) String reason
) {}
