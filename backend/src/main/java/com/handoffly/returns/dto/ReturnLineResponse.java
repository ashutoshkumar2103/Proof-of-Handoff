package com.handoffly.returns.dto;

import com.handoffly.handoff.ItemCondition;
import com.handoffly.returns.ReturnLine;

import java.math.BigDecimal;

public record ReturnLineResponse(
        Long id,
        Long itemId,
        String itemName,
        BigDecimal quantity,
        ItemCondition condition,
        String note
) {
    public static ReturnLineResponse from(ReturnLine line) {
        return new ReturnLineResponse(
                line.getId(),
                line.getItem().getId(),
                line.getItem().getName(),
                line.getQuantity(),
                line.getCondition(),
                line.getNote());
    }
}
