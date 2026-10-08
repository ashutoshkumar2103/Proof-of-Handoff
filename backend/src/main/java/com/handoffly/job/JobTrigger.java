package com.handoffly.job;

/** What started a run of a job. */
public enum JobTrigger {
    /** The scheduler, because the job's time had come. */
    SCHEDULED,
    /** The customer pressed Run now on one job. */
    RUN_NOW,
    /** The customer pressed Run All Now (each of the jobs it runs is its own run). */
    RUN_ALL_NOW
}
