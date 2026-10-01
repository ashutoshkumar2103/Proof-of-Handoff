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

    public record LineComparison(
            String name,
            BigDecimal referenceQuantity,
            BigDecimal targetQuantity,
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
