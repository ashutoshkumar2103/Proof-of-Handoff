package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;
import com.handoffly.job.JobTrigger;
import com.handoffly.job.JobType;

import java.time.Instant;
import java.util.List;

/**
 * One run in a job's history. {@code summary} is the line the Result column shows; the two lists and the safe
 * {@code failureReason} are the same facts in structure, for the details. {@code trigger} is null only for the runs that
 * were copied from before the history existed.
 */
public record JobHistoryResponse(
        Long id,
        JobType type,
        String title,
        JobTrigger trigger,
        Instant runAt,
        JobStatus status,
        String summary,
        List<String> successfulHandoffs,
        List<String> failedHandoffs,
        String failureReason
) {}
