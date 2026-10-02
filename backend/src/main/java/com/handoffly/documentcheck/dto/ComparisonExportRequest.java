package com.handoffly.documentcheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Asks for a finished comparison as a file. It carries the same request the comparison itself takes, so the file is
 * produced by running that very comparison again — it always agrees with what was shown, and nothing is stored.
 * The file names are only labels for the document; they are not read from anywhere.
 */
public record ComparisonExportRequest(
        @Size(max = 255) String fileAName,
        @Size(max = 255) String fileBName,
        @NotNull @Valid CompareRequest comparison
) {}
