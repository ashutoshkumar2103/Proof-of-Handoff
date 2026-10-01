package com.handoffly.handoff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Destination for the emailed Proof-of-Handoff PDF; defaults to the handoff's recipient when blank. */
public record EmailPdfRequest(
        @Email @Size(max = 255) String to
) {}
