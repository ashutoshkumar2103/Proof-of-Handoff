package com.handoffly.job;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One customer's setting for one kind of job: whether it is on, when it runs, and the result of its latest run
 * (only the latest — it is overwritten, not accumulated). A job belongs to exactly one customer and is only ever
 * read, changed or run for that customer; there is at most one per customer and kind.
 */
@Entity
@Table(name = "customer_job")
public class CustomerJob extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 30)
    private JobType type;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Column(nullable = false, length = 60)
    private String timezone;

    /** When the scheduler will next run it; null while it is switched off. */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 20)
    private JobStatus lastStatus;

    /** The references of the handoffs the last run told the customer about, comma separated. */
    @Column(name = "last_handoffs", length = 2000)
    private String lastHandoffs;

    @Column(name = "last_detail", length = 500)
    private String lastDetail;

    protected CustomerJob() {
        // JPA
    }

    public CustomerJob(User user, JobType type, String cronExpression, String timezone) {
        this.user = user;
        this.type = type;
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.enabled = false;   // nothing is sent until the customer turns the job on
    }

    public void schedule(String cronExpression, String timezone, Instant nextRunAt) {
        this.cronExpression = cronExpression;
        this.timezone = timezone;
        this.nextRunAt = enabled ? nextRunAt : null;
    }

    public void setEnabled(boolean enabled, Instant nextRunAt) {
        this.enabled = enabled;
        this.nextRunAt = enabled ? nextRunAt : null;
    }

    /** Remembers how the latest run went, replacing the one before. The schedule is not touched. */
    public void recordRun(Instant at, JobStatus status, String handoffs, String detail) {
        this.lastRunAt = at;
        this.lastStatus = status;
        this.lastHandoffs = handoffs;
        this.lastDetail = detail;
    }

    public User getUser() { return user; }
    public JobType getType() { return type; }
    public boolean isEnabled() { return enabled; }
    public String getCronExpression() { return cronExpression; }
    public String getTimezone() { return timezone; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public JobStatus getLastStatus() { return lastStatus; }
    public String getLastHandoffs() { return lastHandoffs; }
    public String getLastDetail() { return lastDetail; }
}
