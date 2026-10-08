package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;
import com.handoffly.job.JobType;

import java.time.Instant;
import java.util.List;

/**
 * What one run of a job did: how it ended, which handoffs its one email told the customer about, which could not be
 * included (with the safe reason in {@code message}), and when. {@code summary} is the same line the job's history shows.
 */
public record JobRunResponse(JobType type, String title, JobStatus status, List<String> handoffs, Instant runAt, String message,
                             List<String> failedHandoffs, String summary) {}
