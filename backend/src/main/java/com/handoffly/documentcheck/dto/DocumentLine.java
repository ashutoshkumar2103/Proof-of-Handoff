package com.handoffly.documentcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A single line (item + quantity) in a document being compared. */
public record DocumentLine(
        @NotBlank @Size(max = 300) String name,
        BigDecimal quantity
) {}
