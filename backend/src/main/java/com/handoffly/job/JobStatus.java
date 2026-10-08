package com.handoffly.job;

/** How a run of a job ended. */
public enum JobStatus {
    /** One email with everything the job found was sent: every eligible handoff was included in it. */
    SENT,
    /** The email was sent, but only with some of the eligible handoffs: the others could not be prepared for it. */
    PARTIAL,
    /** The job ran and found nothing to tell the customer, so it sent nothing. */
    NOTHING_TO_REPORT,
    /** Nothing was delivered: the email could not be sent (or no handoff could be prepared for it). */
    FAILED
}
