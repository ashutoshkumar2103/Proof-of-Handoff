package com.handoffly.job.dto;

import com.handoffly.job.JobChangeAction;
import com.handoffly.job.JobType;

import java.time.Instant;

/**
 * One change to the customer's job settings: what it was, what it became, who made it and when. {@code previousValue} and
 * {@code newValue} are text as the job showed them ("0 0 9 * * * · Asia/Kolkata", "Active", "Paused").
 */
public record JobChangeResponse(
        Long id,
        JobType type,
        String title,
        JobChangeAction action,
        String previousValue,
        String newValue,
        String performedBy,
        Instant changedAt
) {}
