package com.handoffly.documentcheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A comparison request. The reference document is always supplied inline. The target is
 * either an existing handoff ({@code handoffId}) — so handoff data is never duplicated —
 * or another inline document ({@code targetLines}/{@code targetFields}).
 */
public record CompareRequest(
        @Size(max = 120) String referenceLabel,
        @NotEmpty @Size(max = MAX_LINES) List<@Valid DocumentLine> referenceLines,
        List<@Valid DocumentField> referenceFields,

        Long handoffId,

        @Size(max = 120) String targetLabel,
        @Size(max = MAX_LINES) List<@Valid DocumentLine> targetLines,
        List<@Valid DocumentField> targetFields
) {
    /** The same most-rows limit as a file read by the extractor. */
    public static final int MAX_LINES = 2000;
}
