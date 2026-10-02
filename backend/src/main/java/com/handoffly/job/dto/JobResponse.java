package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;
import com.handoffly.job.JobType;

import java.time.Instant;
import java.util.List;

/**
 * One of the customer's jobs as the app shows it: how it is set up, when it will next run (the next three times,
 * so the schedule can be understood at a glance), and how its latest run went. {@code lastHandoffs} are the
 * references the latest run told the customer about.
 */
public record JobResponse(
        JobType type,
        String title,
        String description,
        boolean enabled,
        String cronExpression,
        String timezone,
        Instant nextRunAt,
        List<Instant> upcomingRuns,
        Instant lastRunAt,
        JobStatus lastStatus,
        List<String> lastHandoffs,
        String lastDetail
) {}
