package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;

import java.time.Instant;
import java.util.List;

/** The subscription-expiry reminder job as the support portal shows it: its setup, next runs and latest result. */
public record ExpiryJobResponse(
        boolean enabled,
        String cronExpression,
        String timezone,
        int windowDays,
        Instant nextRunAt,
        List<Instant> upcomingRuns,
        Instant lastRunAt,
        JobStatus lastStatus,
        Integer lastCount,
        String lastDetail
) {}
