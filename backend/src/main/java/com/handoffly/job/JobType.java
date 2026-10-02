package com.handoffly.job;

/**
 * The five jobs every customer has, each configured separately. The default schedule is a Spring cron expression
 * (second, minute, hour, day of month, month, day of week): the reminders go out every day at 09:00 and the
 * summary on Mondays at 09:00, in the customer's own timezone.
 */
public enum JobType {
    RETURN_REMINDER("Return Reminder", "Handoffs that are due back in the next 2 days.", "0 0 9 * * *"),
    OVERDUE_REMINDER("Overdue Reminder", "Open handoffs whose return date has passed and are not fully returned.", "0 0 9 * * *"),
    MISSING_ITEM_REMINDER("Missing Item Reminder", "Open handoffs that still have items marked missing.", "0 0 9 * * *"),
    WEEKLY_SUMMARY("Weekly Summary", "What happened with your handoffs over the last 7 days.", "0 0 9 * * MON"),
    RECIPIENT_RESPONSE_REMINDER("Recipient Response Reminder", "Sent handoffs whose recipient has not accepted or declined after a day.", "0 0 9 * * *");

    private final String title;
    private final String description;
    private final String defaultCron;

    JobType(String title, String description, String defaultCron) {
        this.title = title;
        this.description = description;
        this.defaultCron = defaultCron;
    }

    public String title() { return title; }
    public String description() { return description; }
    public String defaultCron() { return defaultCron; }
}
