package com.handoffly.job;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.web.RateLimiter;
import com.handoffly.handoff.HandoffActivityService;
import com.handoffly.handoff.HandoffActivityService.ReminderLine;
import com.handoffly.job.dto.JobResponse;
import com.handoffly.job.dto.JobRunResponse;
import com.handoffly.notification.CustomerJobEmail;
import com.handoffly.notification.NotificationService;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A customer's four jobs — return reminder, overdue reminder, missing-item reminder and weekly summary — their
 * schedules, running them on demand, and running the ones that are due. Everything is scoped to one customer: each
 * public method takes the customer's id, finds jobs only by that id, and builds its report from that customer's own
 * handoffs, so one customer's job can never read, change or run another's. A run sends ONE email (never one per
 * handoff), or none if there is nothing to say, and only records its own result: running a job by hand never moves
 * its schedule, and a run never changes a handoff.
 */
@Service
public class CustomerJobService {

    private static final Logger log = LoggerFactory.getLogger(CustomerJobService.class);

    private static final int UPCOMING_RUNS = 3;
    private static final int DUE_SOON_DAYS = 2;
    private static final int SUMMARY_DAYS = 7;
    private static final int DUE_BATCH = 200;
    private static final int MAX_REMEMBERED_REFS = 2000;   // the column is 2000 characters
    private static final Duration RUN_WINDOW = Duration.ofHours(1);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final CustomerJobRepository jobs;
    private final UserService users;
    private final JobSchedule schedules;
    private final HandoffActivityService activity;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final HandOfflyProperties properties;
    private final TransactionTemplate tx;

    public CustomerJobService(CustomerJobRepository jobs, UserService users, JobSchedule schedules,
                              HandoffActivityService activity, NotificationService notifications, RateLimiter limiter,
                              HandOfflyProperties properties, PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.users = users;
        this.schedules = schedules;
        this.activity = activity;
        this.notifications = notifications;
        this.limiter = limiter;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ the customer's jobs

    /** The customer's four jobs, set up with their defaults the first time they are looked at. */
    public List<JobResponse> list(Long userId) {
        ensureJobs(userId);
        Instant now = Instant.now();
        return tx.execute(s -> jobs.findByUserIdOrderByIdAsc(userId).stream()
                .sorted(Comparator.comparing(CustomerJob::getType))
                .map(j -> view(j, now)).toList());
    }

    /** Changes when a job runs. Validated first; saving never runs the job. */
    public JobResponse updateSchedule(Long userId, JobType type, String cronExpression, String timezone) {
        Instant now = Instant.now();
        JobSchedule.Valid valid = schedules.validate(cronExpression, timezone, now);
        return tx.execute(s -> {
            CustomerJob job = require(userId, type);
            job.schedule(valid.cron(), valid.zone().getId(), valid.nextAfter(now));
            return view(job, now);
        });
    }

    /** Pauses or resumes a job; its schedule is untouched. */
    public JobResponse setEnabled(Long userId, JobType type, boolean enabled) {
        Instant now = Instant.now();
        return tx.execute(s -> {
            CustomerJob job = require(userId, type);
            job.setEnabled(enabled, enabled ? schedules.read(job.getCronExpression(), job.getTimezone()).nextAfter(now) : null);
            return view(job, now);
        });
    }

    // ------------------------------------------------------------------ running

    /** Runs one of the customer's jobs now. Its schedule, and whether it is on, are not touched. */
    public JobRunResponse runNow(Long userId, JobType type) {
        limiter.hit("job-run:" + userId, properties.getRateLimit().getJobRunsPerUser(), RUN_WINDOW);
        return execute(userId, type, Instant.now());
    }

    /** Runs all four of the customer's jobs now, once each; no schedule is touched and nobody else's job runs. */
    public List<JobRunResponse> runAllNow(Long userId) {
        ensureJobs(userId);
        return Arrays.stream(JobType.values()).map(type -> runNow(userId, type)).toList();
    }

    /**
     * Runs every enabled job whose time has come (called by the scheduler). A due job is claimed first by moving its
     * next run on, so it cannot run twice; a job that fails does not stop the others; a job missed while the
     * application was down runs once, not once per missed time.
     * @return how many jobs ran
     */
    public int runDue(Instant now) {
        List<CustomerJob> due = tx.execute(s -> jobs.findDue(now, PageRequest.of(0, DUE_BATCH)));
        int ran = 0;
        for (CustomerJob job : due == null ? List.<CustomerJob>of() : due) {
            try {
                if (!job.getUser().isEnabled()) {
                    continue;   // a disabled account gets nothing
                }
                Instant next = schedules.read(job.getCronExpression(), job.getTimezone()).nextAfter(now);
                Integer claimed = tx.execute(s -> jobs.claim(job.getId(), job.getNextRunAt(), next));
                if (claimed != null && claimed == 1) {
                    execute(job.getUser().getId(), job.getType(), now);
                    ran++;
                }
            } catch (RuntimeException e) {
                log.warn("A scheduled customer job could not run: {}", e.getMessage());
            }
        }
        return ran;
    }

    // ------------------------------------------------------------------ one run

    /** What a run found to tell the customer: the email (null if nothing) and the references it mentions. */
    private record Report(CustomerJobEmail email, List<String> references) {}

    private record Target(String email, String name, String accountCode, ZoneId zone) {}

    private JobRunResponse execute(Long userId, JobType type, Instant now) {
        Target target = tx.execute(s -> {
            CustomerJob job = require(userId, type);
            User user = job.getUser();
            return new Target(user.getEmail(), user.getDisplayName(), user.getAccountCode(), ZoneId.of(job.getTimezone()));
        });

        Report report = report(userId, type, now, target.zone());
        JobStatus status;
        String detail = null;
        if (report.email() == null) {
            status = JobStatus.NOTHING_TO_REPORT;
        } else {
            try {
                notifications.sendCustomerJob(target.email(), target.name(), target.accountCode(), report.email());
                status = JobStatus.SENT;
            } catch (RuntimeException e) {
                log.warn("A customer job email could not be sent: {}", e.getMessage());
                status = JobStatus.FAILED;
                detail = "The email could not be sent. It will be tried again at the next run.";
            }
        }

        JobStatus recordedStatus = status;
        String recordedDetail = detail;
        String remembered = remember(report.references());
        tx.executeWithoutResult(s -> require(userId, type).recordRun(now, recordedStatus, remembered, recordedDetail));
        return new JobRunResponse(type, type.title(), status, report.references(), now, detail);
    }

    /** Builds the one email a run sends, from this customer's own handoffs only. Null when there is nothing to say. */
    private Report report(Long userId, JobType type, Instant now, ZoneId zone) {
        return switch (type) {
            case RETURN_REMINDER -> reminder(activity.returnsDueSoon(userId, now, zone, DUE_SOON_DAYS), zone,
                    "Return Reminder", "These handoffs are due back within the next " + DUE_SOON_DAYS + " days:",
                    "Record each return in HandOffly as the items come back.");
            case OVERDUE_REMINDER -> reminder(activity.overdue(userId), zone,
                    "Overdue Handoffs", "These handoffs are past their return date and have not been fully returned:",
                    "Ask the recipients to return the items, or record what has come back.");
            case MISSING_ITEM_REMINDER -> reminder(activity.withMissingItems(userId), zone,
                    "Missing Item Reminder", "These open handoffs still have items marked missing:",
                    "Ask the recipient to confirm the missing items, or close the handoff once they are accounted for.");
            case WEEKLY_SUMMARY -> weekly(userId, now);
        };
    }

    private static Report reminder(List<ReminderLine> found, ZoneId zone, String heading, String intro, String footnote) {
        if (found.isEmpty()) {
            return new Report(null, List.of());
        }
        List<String> lines = found.stream().map(l -> describe(l, zone)).toList();
        return new Report(new CustomerJobEmail(heading, intro, lines, footnote),
                found.stream().map(ReminderLine::reference).toList());
    }

    private Report weekly(Long userId, Instant now) {
        HandoffActivityService.WeeklyActivity a = activity.activity(userId, now.minus(Duration.ofDays(SUMMARY_DAYS)), now);
        if (a.isEmpty()) {
            return new Report(null, List.of());
        }
        List<String> lines = List.of(
                "Handoffs created: " + a.created().size() + refs(a.created()),
                "Handoffs closed: " + a.closed().size() + refs(a.closed()),
                "Handoffs still open: " + a.stillOpen().size() + refs(a.stillOpen()),
                "Items given: " + qty(a.itemsGiven()),
                "Items returned: " + qty(a.itemsReturned()),
                "Items missing now: " + qty(a.itemsMissing()));
        List<String> references = java.util.stream.Stream.of(a.created(), a.closed(), a.stillOpen())
                .flatMap(List::stream).map(HandoffActivityService.Ref::reference).distinct().toList();
        return new Report(new CustomerJobEmail("Weekly Handoff Summary",
                "Here is how your handoffs looked over the last " + SUMMARY_DAYS + " days:", lines,
                "Quantities add up the items as counted on each handoff, whatever their units."), references);
    }

    private static String describe(ReminderLine l, ZoneId zone) {
        StringBuilder s = new StringBuilder(l.reference()).append(" — ").append(l.title()).append(" — ").append(l.recipientName());
        if (l.dueAt() != null) {
            s.append(" — due ").append(DAY.format(l.dueAt().atZone(zone)));
        }
        s.append(" — ").append(qty(l.notReturned())).append(" not yet returned");
        if (l.missing().signum() > 0) {
            s.append(", ").append(qty(l.missing())).append(" marked missing");
        }
        return s.toString();
    }

    private static String refs(List<HandoffActivityService.Ref> list) {
        if (list.isEmpty()) return "";
        return " (" + list.stream().limit(15).map(HandoffActivityService.Ref::reference).reduce((a, b) -> a + ", " + b).orElse("")
                + (list.size() > 15 ? ", …" : "") + ")";
    }

    private static String qty(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.signum() == 0 ? BigDecimal.ZERO : stripped).toPlainString();
    }

    /** The references a run mentioned, kept as one comma-separated text that fits the column. */
    private static String remember(List<String> references) {
        String joined = String.join(", ", references);
        if (joined.length() <= MAX_REMEMBERED_REFS) return joined;
        int cut = joined.lastIndexOf(", ", MAX_REMEMBERED_REFS - 3);
        return joined.substring(0, Math.max(cut, 0)) + ", …";
    }

    // ------------------------------------------------------------------ setting up and showing jobs

    /** Gives the customer a (switched-off) job of each kind they do not have yet. */
    private void ensureJobs(Long userId) {
        try {
            tx.executeWithoutResult(s -> {
                var existing = jobs.findByUserIdOrderByIdAsc(userId).stream().map(CustomerJob::getType).toList();
                if (existing.size() == JobType.values().length) return;
                User user = users.getById(userId);
                for (JobType type : JobType.values()) {
                    if (!existing.contains(type)) {
                        jobs.save(new CustomerJob(user, type, type.defaultCron(), properties.getJobs().getDefaultTimezone()));
                    }
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Another request set them up at the same moment; the unique (customer, kind) rule means nothing is doubled.
        }
    }

    private CustomerJob require(Long userId, JobType type) {
        return jobs.findByUserIdAndType(userId, type).orElseGet(() -> jobs.save(new CustomerJob(
                users.getById(userId), type, type.defaultCron(), properties.getJobs().getDefaultTimezone())));
    }

    private JobResponse view(CustomerJob j, Instant now) {
        List<Instant> upcoming = j.isEnabled()
                ? schedules.read(j.getCronExpression(), j.getTimezone()).upcoming(now, UPCOMING_RUNS) : List.of();
        List<String> handoffs = j.getLastHandoffs() == null || j.getLastHandoffs().isBlank() ? List.of()
                : Arrays.stream(j.getLastHandoffs().split(", ")).toList();
        JobType t = j.getType();
        return new JobResponse(t, t.title(), t.description(), j.isEnabled(), j.getCronExpression(), j.getTimezone(),
                j.getNextRunAt(), upcoming, j.getLastRunAt(), j.getLastStatus(), handoffs, j.getLastDetail());
    }
}
