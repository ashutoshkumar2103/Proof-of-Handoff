package com.handoffly.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new schedule for a job: a cron expression and the timezone it is read in. Checked before it is saved. */
public record UpdateScheduleRequest(
        @NotBlank @Size(max = 100) String cronExpression,
        @NotBlank @Size(max = 60) String timezone
) {}
