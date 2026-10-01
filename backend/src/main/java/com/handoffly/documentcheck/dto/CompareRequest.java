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
        @NotEmpty List<@Valid DocumentLine> referenceLines,
        List<@Valid DocumentField> referenceFields,

        Long handoffId,

        @Size(max = 120) String targetLabel,
        List<@Valid DocumentLine> targetLines,
        List<@Valid DocumentField> targetFields
) {}
