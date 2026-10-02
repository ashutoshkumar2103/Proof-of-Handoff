package com.handoffly.job.dto;

import com.handoffly.job.JobStatus;

import java.time.Instant;
import java.util.List;

/** What one run of the expiry job did: how many customers it reminded (their Account IDs) and how many it could not. */
public record ExpiryJobRunResponse(JobStatus status, int reminded, List<String> accountCodes, int failed, Instant runAt,
                                   String message) {}
