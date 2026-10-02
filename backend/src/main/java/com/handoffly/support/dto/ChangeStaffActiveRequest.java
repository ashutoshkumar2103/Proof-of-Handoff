package com.handoffly.support.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Deactivate ({@code active=false}) or reactivate ({@code active=true}) a staff member. */
public record ChangeStaffActiveRequest(@NotNull Boolean active, @Size(max = 500) String reason) {}
