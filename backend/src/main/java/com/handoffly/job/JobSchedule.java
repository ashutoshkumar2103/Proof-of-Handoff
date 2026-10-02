package com.handoffly.job;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything about a job's schedule that must be right before it is saved: the cron expression is parsed here and
 * nowhere else is it interpreted (a cron expression is only ever a list of times; it never runs anything), the
 * timezone must be a real one, and the schedule may not fire more often than the configured minimum gap — so one
 * customer cannot ask for a job that runs every few seconds. Spring's six-field form: second, minute, hour, day of
 * month, month, day of week.
 */
@Component
public class JobSchedule {

    /** A parsed, accepted schedule. */
    public record Valid(String cron, ZoneId zone, CronExpression expression) {

        /** The first run after {@code after}. */
        public Instant nextAfter(Instant after) {
            ZonedDateTime next = expression.next(after.atZone(zone));
            return next == null ? null : next.toInstant();
        }

        /** The next {@code count} runs after {@code after}. */
        public List<Instant> upcoming(Instant after, int count) {
            List<Instant> runs = new ArrayList<>();
            Instant at = after;
            for (int i = 0; i < count; i++) {
                at = nextAfter(at);
                if (at == null) break;
                runs.add(at);
            }
            return runs;
        }
    }

    private static final int MAX_CRON_LENGTH = 100;
    /** Only what a cron expression is written with: no control characters, quotes or anything stranger. */
    private static final Pattern CRON_CHARACTERS = Pattern.compile("[0-9A-Za-z*/,?#@\\- ]+");
    /** How far ahead the gap between runs is checked: enough to expose a schedule that sometimes fires in a burst. */
    private static final int RUNS_CHECKED = 40;

    private final Duration minInterval;

    public JobSchedule(HandOfflyProperties properties) {
        this.minInterval = Duration.ofMinutes(properties.getJobs().getMinIntervalMinutes());
    }

    /**
     * @throws BadRequestException if the expression or the timezone is not valid, never runs, or runs more often
     *                             than the minimum gap allows
     */
    public Valid validate(String cronExpression, String timezone, Instant now) {
        String cron = cronExpression == null ? "" : cronExpression.trim().replaceAll("\\s+", " ");
        if (cron.isEmpty() || cron.length() > MAX_CRON_LENGTH || !CRON_CHARACTERS.matcher(cron).matches()) {
            throw new BadRequestException("That is not a valid schedule. Use six fields: second, minute, hour, "
                    + "day of month, month, day of week — for example 0 0 9 * * * for every day at 09:00.");
        }
        ZoneId zone = zoneOf(timezone);
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("That is not a valid schedule. Use six fields: second, minute, hour, "
                    + "day of month, month, day of week — for example 0 0 9 * * * for every day at 09:00.");
        }

        Valid valid = new Valid(cron, zone, expression);
        List<Instant> runs = valid.upcoming(now, RUNS_CHECKED);
        if (runs.isEmpty()) {
            throw new BadRequestException("That schedule never runs. Check the day and month fields.");
        }
        for (int i = 1; i < runs.size(); i++) {
            if (Duration.between(runs.get(i - 1), runs.get(i)).compareTo(minInterval) < 0) {
                throw new BadRequestException("That schedule would run too often. Jobs can run at most once every "
                        + minInterval.toMinutes() + " minutes.");
            }
        }
        return valid;
    }

    /** A saved schedule is trusted to have been validated, so this just reads it. */
    public Valid read(String cron, String timezone) {
        return new Valid(cron, ZoneId.of(timezone), CronExpression.parse(cron));
    }

    private static ZoneId zoneOf(String timezone) {
        String name = timezone == null ? "" : timezone.trim();
        if (name.isEmpty() || !ZoneId.getAvailableZoneIds().contains(name)) {
            throw new BadRequestException("That timezone is not recognised. Use a name such as Asia/Kolkata or Europe/London.");
        }
        return ZoneId.of(name);
    }
}
