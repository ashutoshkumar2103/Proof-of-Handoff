package com.handoffly.handoff.dto;

import com.handoffly.handoff.ItemCondition;

import java.math.BigDecimal;

/**
 * An item with its outgoing quantity and computed return position. {@code remaining}
 * is always {@code outgoing - returnedConfirmed}, computed by the backend so clients
 * never do the arithmetic themselves.
 */
public record HandoffItemResponse(
        Long id,
        String name,
        String description,
        String sku,
        String serialNumber,
        String assetNumber,
        String unit,
        ItemCondition condition,
        String notes,
        BigDecimal outgoing,
        BigDecimal returnedConfirmed,
        BigDecimal missing,
        BigDecimal returnedPending,
        BigDecimal remaining
) {}
