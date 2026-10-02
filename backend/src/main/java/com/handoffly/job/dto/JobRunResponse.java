package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;
import com.handoffly.job.JobType;

import java.time.Instant;
import java.util.List;

/** What one run of a job did: whether it sent its email, which handoffs it told the customer about, and when. */
public record JobRunResponse(JobType type, String title, JobStatus status, List<String> handoffs, Instant runAt, String message) {}
