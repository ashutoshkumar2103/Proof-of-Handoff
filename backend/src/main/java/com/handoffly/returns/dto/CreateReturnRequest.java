package com.handoffly.returns.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Records a return event against a handoff. May contain any subset of the handoff's
 * items and any quantities up to what remains outstanding for each.
 */
public record CreateReturnRequest(
        Instant occurredAt,
        @Size(max = 1000) String note,
        @NotEmpty List<@Valid ReturnLineRequest> lines
) {}
