package com.handoffly.handoff.dto;

import com.handoffly.handoff.ItemCondition;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** An outgoing item on a handoff being created or edited (draft only). */
public record HandoffItemRequest(
        @NotBlank @Size(max = 300) String name,
        @Size(max = 1000) String description,
        @Size(max = 80) String sku,
        @Size(max = 120) String serialNumber,
        @Size(max = 120) String assetNumber,
        @NotNull @DecimalMin(value = "0.001") @Digits(integer = 16, fraction = 3) BigDecimal quantity,
        @Size(max = 30) String unit,
        ItemCondition condition,
        @Size(max = 1000) String notes
) {}
