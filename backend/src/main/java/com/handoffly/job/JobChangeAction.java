package com.handoffly.job;

/** The changes to a job's settings that are recorded. */
public enum JobChangeAction {
    /** The cron expression, the timezone, or both (previous and new value read "cron · timezone"). */
    SCHEDULE_CHANGED,
    PAUSED,
    RESUMED
}
