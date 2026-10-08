package com.handoffly.documentcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Two spellings the customer has accepted as one item (for example from an AI Assist suggestion): every line named {@code from}, in either
 * file, is compared under the name {@code to}. The lines themselves are not changed.
 */
public record NameMatch(@NotBlank @Size(max = 300) String from, @NotBlank @Size(max = 300) String to) {}
