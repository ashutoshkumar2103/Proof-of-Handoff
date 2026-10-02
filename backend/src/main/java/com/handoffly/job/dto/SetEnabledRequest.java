package com.handoffly.job.dto;

import jakarta.validation.constraints.NotNull;

/** Pausing (false) or resuming (true) a job. Its schedule is left as it is. */
public record SetEnabledRequest(@NotNull Boolean enabled) {}
