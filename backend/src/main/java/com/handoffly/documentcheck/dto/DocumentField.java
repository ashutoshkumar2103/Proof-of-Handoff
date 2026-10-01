package com.handoffly.documentcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A single named field (e.g. "Delivery date") to compare across documents. */
public record DocumentField(
        @NotBlank @Size(max = 120) String label,
        @Size(max = 500) String value
) {}
