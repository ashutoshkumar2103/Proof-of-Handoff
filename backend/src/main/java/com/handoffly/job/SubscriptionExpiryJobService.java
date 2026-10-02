package com.handoffly.job;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.job.dto.ExpiryJobResponse;
import com.handoffly.job.dto.ExpiryJobRunResponse;
import com.handoffly.notification.CustomerJobEmail;
import com.handoffly.notification.NotificationService;
import com.handoffly.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The support team's one platform-wide job: email each customer whose subscription is about to end, once per end date.
 * It only reads subscriptions and sends mail — it never changes a plan, an end date or a customer. Who may use it
 * (administrators and managers) is decided at the controller; this class is the job itself: its settings, running it
 * by hand or on schedule, and making sure no customer is reminded twice about the same period.
 */
@Service
public class SubscriptionExpiryJobService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryJobService.class);

    private static final SupportJobType TYPE = SupportJobType.SUBSCRIPTION_EXPIRY_REMINDER;
    private static final String DEFAULT_CRON = "0 0 9 * * *";
    private static final int UPCOMING_RUNS = 3;
    private static final int BATCH = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final SupportJobRepository jobs;
    private final SubscriptionExpiryReminderRepository reminders;
    private final JobSchedule schedules;
    private final NotificationService notifications;
    private final HandOfflyProperties properties;
    private final TransactionTemplate tx;

    public SubscriptionExpiryJobService(SupportJobRepository jobs, SubscriptionExpiryReminderRepository reminders,
                                        JobSchedule schedules, NotificationService notifications,
                                        HandOfflyProperties properties, PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.reminders = reminders;
        this.schedules = schedules;
        this.notifications = notifications;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ the job's settings

    public ExpiryJobResponse get() {
        ensureJob();
        Instant now = Instant.now();
        return tx.execute(s -> view(require(), now));
    }

    /** Changes when the job runs and how far ahead it looks. Validated first; saving never runs the job. */
    public ExpiryJobResponse update(String cronExpression, String timezone, int windowDays) {
        Instant now = Instant.now();
        JobSchedule.Valid valid = schedules.validate(cronExpression, timezone, now);
        ensureJob();
        return tx.execute(s -> {
            SupportJob job = require();
            job.schedule(valid.cron(), valid.zone().getId(), windowDays, valid.nextAfter(now));
            return view(job, now);
        });
    }

    /** Pauses or resumes the job; its schedule and window are untouched. */
    public ExpiryJobResponse setEnabled(boolean enabled) {
        Instant now = Instant.now();
        ensureJob();
        return tx.execute(s -> {
            SupportJob job = require();
            job.setEnabled(enabled, enabled ? schedules.read(job.getCronExpression(), job.getTimezone()).nextAfter(now) : null);
            return view(job, now);
        });
    }

    // ------------------------------------------------------------------ running

    /** Runs the job now. Whether it is on, and when it next runs, are not touched. */
    public ExpiryJobRunResponse runNow() {
        ensureJob();
        return execute(Instant.now());
    }

    /**
     * Runs the job if it is on and its time has come (called by the scheduler). It is claimed first by moving its
     * next run on, so two passes or two instances cannot both run it; a job missed while the application was down
     * runs once.
     * @return whether it ran
     */
    public boolean runDue(Instant now) {
        List<SupportJob> due = tx.execute(s -> jobs.findDue(now));
        for (SupportJob job : due == null ? List.<SupportJob>of() : due) {
            if (job.getType() != TYPE) continue;
            try {
                Instant next = schedules.read(job.getCronExpression(), job.getTimezone()).nextAfter(now);
                Integer claimed = tx.execute(s -> jobs.claim(job.getId(), job.getNextRunAt(), next));
                if (claimed != null && claimed == 1) {
                    execute(now);
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("The subscription-expiry job could not run: {}", e.getMessage());
            }
        }
        return false;
    }

    private ExpiryJobRunResponse execute(Instant now) {
        SupportJob settings = tx.execute(s -> require());
        ZoneId zone = ZoneId.of(settings.getTimezone());
        Instant until = now.plus(Duration.ofDays(settings.getWindowDays()));
        List<User> eligible = tx.execute(s -> reminders.findDueForReminder(now, until, PageRequest.of(0, BATCH)));

        List<String> reminded = new ArrayList<>();
        int failed = 0;
        for (User user : eligible == null ? List.<User>of() : eligible) {
            Instant validUntil = user.getPlanValidUntil();
            if (!reserve(user, validUntil)) {
                continue;   // already reminded about this end date (a run overlapping this one got there first)
            }
            try {
                notifications.sendCustomerJob(user.getEmail(), user.getDisplayName(), user.getAccountCode(),
                        email(user, validUntil, zone));
                reminded.add(user.getAccountCode());
            } catch (RuntimeException e) {
                log.warn("A subscription-expiry reminder could not be sent: {}", e.getMessage());
                release(user, validUntil);   // so the next run tries this customer again
                failed++;
            }
        }

        JobStatus status = failed > 0 ? JobStatus.FAILED : reminded.isEmpty() ? JobStatus.NOTHING_TO_REPORT : JobStatus.SENT;
        String detail = failed > 0
                ? failed + " reminder" + (failed == 1 ? "" : "s") + " could not be emailed; they will be tried again at the next run."
                : null;
        int count = reminded.size();
        tx.executeWithoutResult(s -> require().recordRun(now, status, count, detail));
        return new ExpiryJobRunResponse(status, count, reminded, failed, now, detail);
    }

    /** Writes down that this customer is being reminded about this end date. False if that was already done. */
    private boolean reserve(User user, Instant validUntil) {
        try {
            tx.executeWithoutResult(s -> reminders.saveAndFlush(
                    new SubscriptionExpiryReminder(user.getId(), validUntil, user.getSubscriptionPlan())));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /** Takes back a reservation whose email failed. */
    private void release(User user, Instant validUntil) {
        tx.executeWithoutResult(s -> reminders.deleteByUserIdAndValidUntil(user.getId(), validUntil));
    }

    private static CustomerJobEmail email(User user, Instant validUntil, ZoneId zone) {
        String plan = planName(user);
        String lastDay = DAY.format(validUntil.minusSeconds(1).atZone(zone));
        return new CustomerJobEmail("Subscription Expiring Soon",
                "Your " + plan + " plan is paid up to " + lastDay + ".",
                List.of("Plan: " + plan, "Last day: " + lastDay,
                        "After that, the support features of your plan pause. Your handoffs and the rest of HandOffly keep working."),
                "To keep your support features, renew your plan from the Account page in HandOffly.");
    }

    private static String planName(User user) {
        String[] words = user.getSubscriptionPlan().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (name.length() > 0) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    // ------------------------------------------------------------------ setting up and showing

    /** Gives the platform its (switched-off) expiry job the first time it is looked at. */
    private void ensureJob() {
        try {
            tx.executeWithoutResult(s -> require());
        } catch (DataIntegrityViolationException e) {
            // Another request created it at the same moment; the unique job type means there is still only one.
        }
    }

    private SupportJob require() {
        return jobs.findByType(TYPE).orElseGet(() -> jobs.save(new SupportJob(TYPE, DEFAULT_CRON,
                properties.getJobs().getDefaultTimezone(), properties.getJobs().getExpiryWindowDays())));
    }

    private ExpiryJobResponse view(SupportJob j, Instant now) {
        List<Instant> upcoming = j.isEnabled()
                ? schedules.read(j.getCronExpression(), j.getTimezone()).upcoming(now, UPCOMING_RUNS) : List.of();
        return new ExpiryJobResponse(j.isEnabled(), j.getCronExpression(), j.getTimezone(), j.getWindowDays(),
                j.getNextRunAt(), upcoming, j.getLastRunAt(), j.getLastStatus(), j.getLastCount(), j.getLastDetail());
    }
}
