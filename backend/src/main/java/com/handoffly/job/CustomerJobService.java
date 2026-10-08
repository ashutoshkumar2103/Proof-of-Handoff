package com.handoffly.job;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.web.PageResponse;
import com.handoffly.common.web.RateLimiter;
import com.handoffly.handoff.HandoffActivityService;
import com.handoffly.handoff.HandoffActivityService.ReminderLine;
import com.handoffly.job.dto.JobHistoryResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A customer's five jobs — return reminder, overdue reminder, missing-item reminder, weekly summary and recipient response reminder — their
 * schedules, running them on demand, and running the ones that are due. Everything is scoped to one customer: each
 * public method takes the customer's id, finds jobs only by that id, and builds its report from that customer's own
 * handoffs, so one customer's job can never read, change or run another's. A run sends ONE email (never one per
 * handoff), or none if there is nothing to say, with only the handoffs that could be prepared for it, and writes ONE
 * history row ({@link CustomerJobRun}) that keeps what was sent and what was not. Running a job by hand never moves its
 * schedule, and a run never changes a handoff.
 */
@Service
public class CustomerJobService {

    private static final Logger log = LoggerFactory.getLogger(CustomerJobService.class);

    private static final int UPCOMING_RUNS = 3;
    private static final int DUE_SOON_DAYS = 2;
    private static final int SUMMARY_DAYS = 7;
    /** How long a sent handoff may wait for its recipient before the response reminder mentions it. */
    private static final Duration RESPONSE_WAIT = Duration.ofHours(24);
    private static final int DUE_BATCH = 200;
    private static final int MAX_REMEMBERED_REFS = 2000;   // the column is 2000 characters
    private static final int MAX_DETAIL = 500;             // customer_job.last_detail is 500 characters
    private static final int MAX_HISTORY_PAGE = 100;
    private static final Duration RUN_WINDOW = Duration.ofHours(1);
    /** The reasons a run records. Always one of these two, never an exception's own message: what failed inside stays in the log. */
    private static final String EMAIL_NOT_SENT = "The email could not be sent. It will be tried again at the next run.";
    private static final String NOT_PREPARED = "Their details could not be prepared for the email. They will be tried again at the next run.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final CustomerJobRepository jobs;
    private final CustomerJobRunRepository runs;
    private final UserService users;
    private final JobSchedule schedules;
    private final HandoffActivityService activity;
    private final NotificationService notifications;
    private final RateLimiter limiter;
    private final HandOfflyProperties properties;
    private final TransactionTemplate tx;

    public CustomerJobService(CustomerJobRepository jobs, CustomerJobRunRepository runs, UserService users, JobSchedule schedules,
                              HandoffActivityService activity, NotificationService notifications, RateLimiter limiter,
                              HandOfflyProperties properties, PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.runs = runs;
        this.users = users;
        this.schedules = schedules;
        this.activity = activity;
        this.notifications = notifications;
        this.limiter = limiter;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ the customer's jobs

    /** The customer's jobs, set up with their defaults the first time they are looked at. */
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
        return runByHand(userId, type, JobTrigger.RUN_NOW);
    }

    /** Runs all of the customer's jobs now, once each (each one is its own run); no schedule is touched and nobody else's job runs. */
    public List<JobRunResponse> runAllNow(Long userId) {
        ensureJobs(userId);
        return Arrays.stream(JobType.values()).map(type -> runByHand(userId, type, JobTrigger.RUN_ALL_NOW)).toList();
    }

    private JobRunResponse runByHand(Long userId, JobType type, JobTrigger trigger) {
        limiter.hit("job-run:" + userId, properties.getRateLimit().getJobRunsPerUser(), RUN_WINDOW);
        return execute(userId, type, Instant.now(), trigger);
    }

    /** The customer's runs of every job, newest first — the Job History. */
    public PageResponse<JobHistoryResponse> history(Long userId, Pageable requested) {
        Pageable page = PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), MAX_HISTORY_PAGE),
                Sort.by(Sort.Direction.DESC, "id"));
        return tx.execute(s -> PageResponse.of(runs.findByUserId(userId, page), r -> new JobHistoryResponse(
                r.getId(), r.getType(), r.getType().title(), r.getTrigger(), r.getRunAt(), r.getStatus(), r.summary(),
                r.sentRefs(), r.failedRefs(), r.getDetail())));
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
                    execute(job.getUser().getId(), job.getType(), now, JobTrigger.SCHEDULED);
                    ran++;
                }
            } catch (RuntimeException e) {
                log.warn("A scheduled customer job could not run: {}", e.getMessage());
            }
        }
        return ran;
    }

    // ------------------------------------------------------------------ one run

    /**
     * What a run found to tell the customer: the one email (null when no handoff could be put in it), the references of the
     * handoffs in it, and the references of eligible handoffs that could not be prepared for it.
     */
    private record Report(CustomerJobEmail email, List<String> included, List<String> failed) {}

    private record Target(String email, String name, String accountCode, ZoneId zone) {}

    /**
     * One run of one job: it sends at most ONE email, with the handoffs that could be prepared for it, and writes ONE history row
     * that keeps both sides — what was sent and what was not. If the email itself cannot be sent, nothing was delivered, so the whole
     * run is FAILED and none of its handoffs counts as sent.
     */
    private JobRunResponse execute(Long userId, JobType type, Instant now, JobTrigger trigger) {
        Target target = tx.execute(s -> {
            CustomerJob job = require(userId, type);
            User user = job.getUser();
            return new Target(user.getEmail(), user.getDisplayName(), user.getAccountCode(), ZoneId.of(job.getTimezone()));
        });

        Report report = report(userId, type, now, target.zone(), alreadyTold(userId, type));
        JobStatus status;
        List<String> sent = List.of();
        List<String> failed = report.failed();
        String reason = null;
        if (report.email() == null) {
            status = failed.isEmpty() ? JobStatus.NOTHING_TO_REPORT : JobStatus.FAILED;   // eligible, yet none could go in the email
            reason = failed.isEmpty() ? null : NOT_PREPARED;
        } else {
            try {
                notifications.sendCustomerJob(target.email(), target.name(), target.accountCode(), report.email());
                sent = report.included();
                status = failed.isEmpty() ? JobStatus.SENT : JobStatus.PARTIAL;
                reason = failed.isEmpty() ? null : NOT_PREPARED;
            } catch (RuntimeException e) {
                log.warn("A customer job email could not be sent: {}", e.getMessage());
                status = JobStatus.FAILED;
                reason = EMAIL_NOT_SENT;
                failed = Stream.concat(report.included().stream(), failed.stream()).toList();   // nothing was delivered
            }
        }

        JobStatus recordedStatus = status;
        String sentText = remember(sent);
        String failedText = remember(failed);
        String note = CustomerJobRun.failureNote(status, failed, reason);
        String recordedReason = reason;
        CustomerJobRun run = tx.execute(s -> {
            CustomerJob job = require(userId, type);
            job.recordRun(now, recordedStatus, sentText, limit(note, MAX_DETAIL));
            return runs.save(new CustomerJobRun(job.getUser(), type, trigger, now, recordedStatus, sentText, failedText, recordedReason));
        });
        return new JobRunResponse(type, type.title(), status, sent, now, note, failed, run.summary());
    }

    /**
     * The handoffs this job's previous run already told the customer about, when that run was only partly successful. They are left out
     * of the next run, which is the retry of what failed (plus anything newly eligible), so they are not sent twice for the same
     * unresolved run. Scoped to this customer and this job, read from the job's own history, and gone after one run: from then on the
     * job's ordinary rules apply again. The weekly summary is a summary, not a list of handoffs, so it has nothing to leave out.
     */
    private Set<String> alreadyTold(Long userId, JobType type) {
        if (type == JobType.WEEKLY_SUMMARY) {
            return Set.of();
        }
        Optional<CustomerJobRun> last = tx.execute(s -> runs.findFirstByUserIdAndTypeOrderByIdDesc(userId, type));
        return last == null ? Set.of() : last.filter(r -> r.getStatus() == JobStatus.PARTIAL).map(r -> Set.copyOf(r.sentRefs())).orElse(Set.of());
    }

    /** Builds the one email a run sends, from this customer's own handoffs only. No email when there is nothing to say. */
    private Report report(Long userId, JobType type, Instant now, ZoneId zone, Set<String> alreadyTold) {
        return switch (type) {
            case RETURN_REMINDER -> reminder(activity.returnsDueSoon(userId, now, zone, DUE_SOON_DAYS), ReminderLine::reference,
                    l -> describe(l, zone), alreadyTold,
                    "Return Reminder", "These handoffs are due back within the next " + DUE_SOON_DAYS + " days:",
                    "Record each return in HandOffly as the items come back.");
            case OVERDUE_REMINDER -> reminder(activity.overdue(userId), ReminderLine::reference, l -> describe(l, zone), alreadyTold,
                    "Overdue Handoffs", "These handoffs are past their return date and have not been fully returned:",
                    "Ask the recipients to return the items, or record what has come back.");
            case MISSING_ITEM_REMINDER -> reminder(activity.withMissingItems(userId), ReminderLine::reference, l -> describe(l, zone), alreadyTold,
                    "Missing Item Reminder", "These open handoffs still have items marked missing:",
                    "Ask the recipient to confirm the missing items, or close the handoff once they are accounted for.");
            case WEEKLY_SUMMARY -> weekly(userId, now);
            case RECIPIENT_RESPONSE_REMINDER -> reminder(activity.awaitingRecipient(userId, now, RESPONSE_WAIT),
                    HandoffActivityService.AwaitingLine::reference, l -> describe(l, now, zone), alreadyTold,
                    "Recipient Response Reminder",
                    "These handoffs were sent to their recipients and are still waiting for a response — the recipient has neither accepted nor declined:",
                    "Use Resend link on a handoff if its recipient needs the link again. A handoff drops out of this reminder as soon as its recipient responds.");
        };
    }

    /**
     * Turns each eligible handoff into its line of the email, one at a time, so one that cannot be prepared (it is reported as failed,
     * with no detail of why) never stops the others from being sent. Which handoffs are eligible is decided before this and is not
     * touched here; a handoff in {@code alreadyTold} is skipped.
     */
    private <L> Report reminder(List<L> found, Function<L, String> reference, Function<L, String> describe, Set<String> alreadyTold,
                                String heading, String intro, String footnote) {
        List<String> lines = new ArrayList<>();
        List<String> included = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (L handoff : found) {
            String ref = reference.apply(handoff);
            if (alreadyTold.contains(ref)) {
                continue;
            }
            try {
                lines.add(describe.apply(handoff));
                included.add(ref);
            } catch (RuntimeException e) {
                log.warn("A handoff could not be prepared for a job email ({})", e.getClass().getSimpleName());
                failed.add(ref);
            }
        }
        return new Report(lines.isEmpty() ? null : new CustomerJobEmail(heading, intro, lines, footnote), included, failed);
    }

    private Report weekly(Long userId, Instant now) {
        HandoffActivityService.WeeklyActivity a = activity.activity(userId, now.minus(Duration.ofDays(SUMMARY_DAYS)), now);
        if (a.isEmpty()) {
            return new Report(null, List.of(), List.of());
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
                "Quantities add up the items as counted on each handoff, whatever their units."), references, List.of());
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

    private static String describe(HandoffActivityService.AwaitingLine l, Instant now, ZoneId zone) {
        long days = Duration.between(l.sentAt(), now).toDays();
        return l.reference() + " — " + l.title() + " — " + l.recipientName() + " — sent " + DAY.format(l.sentAt().atZone(zone))
                + " — waiting " + days + (days == 1 ? " day" : " days");
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

    private static String limit(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
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
