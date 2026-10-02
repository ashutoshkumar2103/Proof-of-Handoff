package com.handoffly.job.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new setup for the expiry job: when it runs (checked before it is saved) and how many days ahead it looks. */
public record UpdateExpiryJobRequest(
        @NotBlank @Size(max = 100) String cronExpression,
        @NotBlank @Size(max = 60) String timezone,
        @NotNull @Min(1) @Max(90) Integer windowDays
) {}
