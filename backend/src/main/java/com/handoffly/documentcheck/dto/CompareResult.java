package com.handoffly.documentcheck.dto;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of comparing a reference document against a target. */
public record CompareResult(
        String referenceLabel,
        String targetLabel,
        Summary summary,
        List<LineComparison> lines,
        List<FieldComparison> fields
) {
    public enum MatchStatus {
        MATCH,
        MISMATCH,
        MISSING_IN_TARGET,
        EXTRA_IN_TARGET
    }

    /** {@code difference} is target minus reference; null when the line is on one side only. */
    public record LineComparison(
            String name,
            BigDecimal referenceQuantity,
            BigDecimal targetQuantity,
            BigDecimal difference,
            MatchStatus status
    ) {}

    public record FieldComparison(
            String label,
            String referenceValue,
            String targetValue,
            MatchStatus status
    ) {}

    public record Summary(
            int totalLines,
            int matched,
            int mismatched,
            int missingInTarget,
            int extraInTarget,
            boolean allMatch
    ) {}
}
