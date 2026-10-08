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
import java.util.Arrays;
import java.util.List;

/**
 * One run of one customer's job, as it ended. Append-only: it has no setters and nothing updates or deletes it, so the
 * history of a job is exactly what happened, and a later success never replaces an earlier partial or failed run.
 * It records both sides of a run together: the handoffs the run's one email told the customer about, and the ones that
 * could not be included (with a safe reason), so an operator can see success and failure in a single row.
 */
@Entity
@Table(name = "customer_job_run")
public class CustomerJobRun extends BaseEntity {

    /** Separates the references in the stored text. */
    static final String SEPARATOR = ", ";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 30)
    private JobType type;

    /** Null only on the rows copied from the single "latest run" that existed before the history did. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", updatable = false, length = 20)
    private JobTrigger trigger;

    @Column(name = "run_at", nullable = false, updatable = false)
    private Instant runAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JobStatus status;

    @Column(name = "sent_handoffs", updatable = false, length = 2000)
    private String sentHandoffs;

    @Column(name = "failed_handoffs", updatable = false, length = 2000)
    private String failedHandoffs;

    /** The safe reason the failed handoffs (or the whole email) failed — never an exception message. */
    @Column(updatable = false, length = 500)
    private String detail;

    protected CustomerJobRun() {
        // JPA
    }

    public CustomerJobRun(User user, JobType type, JobTrigger trigger, Instant runAt, JobStatus status,
                          String sentHandoffs, String failedHandoffs, String detail) {
        this.user = user;
        this.type = type;
        this.trigger = trigger;
        this.runAt = runAt;
        this.status = status;
        this.sentHandoffs = sentHandoffs;
        this.failedHandoffs = failedHandoffs;
        this.detail = detail;
    }

    public User getUser() { return user; }
    public JobType getType() { return type; }
    public JobTrigger getTrigger() { return trigger; }
    public Instant getRunAt() { return runAt; }
    public JobStatus getStatus() { return status; }
    public String getDetail() { return detail; }

    /** The handoffs this run's email told the customer about. */
    public List<String> sentRefs() { return split(sentHandoffs); }

    /** The handoffs that were not delivered by this run. */
    public List<String> failedRefs() { return split(failedHandoffs); }

    /** The one-line account of the run, for the Result column. */
    public String summary() {
        return switch (status) {
            case SENT -> "Successfully sent for " + join(sentRefs());
            case PARTIAL -> "Partial success — Successfully sent for " + join(sentRefs()) + "; Failed for " + join(failedRefs())
                    + ": " + detail;
            case NOTHING_TO_REPORT -> "Nothing to report";
            case FAILED -> "Failed — " + detail + (failedRefs().isEmpty() ? "" : " Not sent: " + join(failedRefs()) + ".");
        };
    }

    /** What the Jobs page shows beside the latest run: the failures of a run, which the references beside it do not say. */
    public static String failureNote(JobStatus status, List<String> failed, String reason) {
        return switch (status) {
            case PARTIAL -> "Failed for " + join(failed) + ": " + reason;
            case FAILED -> reason;
            default -> null;
        };
    }

    private static String join(List<String> refs) {
        return String.join(SEPARATOR, refs);
    }

    private static List<String> split(String stored) {
        return stored == null || stored.isBlank() ? List.of() : Arrays.stream(stored.split(SEPARATOR)).toList();
    }
}
