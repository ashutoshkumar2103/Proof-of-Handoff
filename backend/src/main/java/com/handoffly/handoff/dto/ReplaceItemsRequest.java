package com.handoffly.handoff.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replaces the full item list of a DRAFT handoff (add/edit/remove in one operation). */
public record ReplaceItemsRequest(
        @NotNull List<@Valid HandoffItemRequest> items
) {}
