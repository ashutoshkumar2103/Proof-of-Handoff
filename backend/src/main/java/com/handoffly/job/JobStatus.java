package com.handoffly.job;

/** How the last run of a job ended. */
public enum JobStatus {
    /** One email with everything the job found was sent. */
    SENT,
    /** The job ran and found nothing to tell the customer, so it sent nothing. */
    NOTHING_TO_REPORT,
    /** The job ran but its email could not be sent. */
    FAILED
}
