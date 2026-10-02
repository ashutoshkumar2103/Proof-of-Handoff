package com.handoffly.job;

import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The setting for one support-side job: whether it is on, when it runs, how far ahead it looks, and the result of its
 * latest run (overwritten by the next one). There is at most one row per kind of job.
 */
@Entity
@Table(name = "support_job")
public class SupportJob extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 40)
    private SupportJobType type;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Column(nullable = false, length = 60)
    private String timezone;

    /** How many days before a subscription ends its customer is reminded. */
    @Column(name = "window_days", nullable = false)
    private int windowDays;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 20)
    private JobStatus lastStatus;

    /** How many customers the latest run reminded. */
    @Column(name = "last_count")
    private Integer lastCount;

    @Column(name = "last_detail", length = 500)
    private String lastDetail;

    protected SupportJob() {
        // JPA
    }

    public SupportJob(SupportJobType type, String cronExpression, String timezone, int windowDays) {
        this.type = type;
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.windowDays = windowDays;
        this.enabled = false;   // nothing is sent until staff turn the job on
    }

    public void schedule(String cronExpression, String timezone, int windowDays, Instant nextRunAt) {
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.windowDays = windowDays;
        this.nextRunAt = enabled ? nextRunAt : null;
    }

    public void setEnabled(boolean enabled, Instant nextRunAt) {
        this.enabled = enabled;
        this.nextRunAt = enabled ? nextRunAt : null;
    }

    /** Remembers how the latest run went, replacing the one before. The schedule is not touched. */
    public void recordRun(Instant at, JobStatus status, int count, String detail) {
        this.lastRunAt = at;
        this.lastStatus = status;
        this.lastCount = count;
        this.lastDetail = detail;
    }

    public SupportJobType getType() { return type; }
    public boolean isEnabled() { return enabled; }
    public String getCronExpression() { return cronExpression; }
    public String getTimezone() { return timezone; }
    public int getWindowDays() { return windowDays; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public JobStatus getLastStatus() { return lastStatus; }
    public Integer getLastCount() { return lastCount; }
    public String getLastDetail() { return lastDetail; }
}
