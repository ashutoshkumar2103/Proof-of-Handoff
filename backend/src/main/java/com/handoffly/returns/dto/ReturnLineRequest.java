package com.handoffly.returns.dto;

import com.handoffly.handoff.ItemCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ReturnLineRequest(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.001") @Digits(integer = 16, fraction = 3) BigDecimal quantity,
        ItemCondition condition,
        @Size(max = 1000) String note
) {}
