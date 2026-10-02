package com.handoffly.job;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which schedules are accepted: real cron expressions in a real timezone that do not run too often or never. */
class JobScheduleTest {

    private static final Instant NOW = ZonedDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private final JobSchedule schedule = new JobSchedule(new HandOfflyProperties());   // at most once an hour

    @Test
    void sensibleSchedulesAreAccepted() {
        for (String cron : List.of("0 0 9 * * *", "0 30 8 * * MON-FRI", "0 0 */2 * * *", "0 0 9 1 * *", "0 0 9 * * MON",
                "0 0 0 * * *", "0 15 10 ? * *", "@daily", "@hourly", "  0   0  9  *  *  *  ",
                "0 0 9 * * *\n", "0 0 9,9 * * *")) {   // surrounding whitespace is normalised; 9,9 is just 9
            assertThatCode(() -> schedule.validate(cron, "UTC", NOW)).as(cron).doesNotThrowAnyException();
        }
    }

    @Test
    void theNextRunsFollowTheScheduleInTheChosenTimezone() {
        JobSchedule.Valid daily = schedule.validate("0 0 9 * * *", "Asia/Kolkata", NOW);   // 09:00 India = 03:30 UTC
        assertThat(daily.upcoming(NOW, 3)).containsExactly(
                ZonedDateTime.of(2026, 10, 3, 3, 30, 0, 0, ZoneOffset.UTC).toInstant(),
                ZonedDateTime.of(2026, 10, 4, 3, 30, 0, 0, ZoneOffset.UTC).toInstant(),
                ZonedDateTime.of(2026, 10, 5, 3, 30, 0, 0, ZoneOffset.UTC).toInstant());
        assertThat(schedule.validate("0 0 9 * * MON", "UTC", NOW).nextAfter(NOW))
                .isEqualTo(ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, ZoneOffset.UTC).toInstant());   // the next Monday
        assertThat(schedule.read("0 0 9 * * *", "UTC").nextAfter(NOW))
                .isEqualTo(ZonedDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.UTC).toInstant());   // later today
    }

    @Test
    void anythingThatIsNotACronExpressionIsRefused() {
        for (String cron : new String[]{null, "", "   ", "not a cron", "* * * * *", "0 0 9 * * * *", "0 0 25 * * *",
                "0 61 9 * * *", "0 0 9 * 13 *", "0 0 9 * * FUNDAY", "9 am daily", "0 0 9 * * *; DROP TABLE app_user",
                "0 0 9 * * * && echo hi", "$(whoami)", "0 0 9 * * *" + " ".repeat(5) + "x".repeat(120)}) {
            assertThatThrownBy(() -> schedule.validate(cron, "UTC", NOW)).as("[" + cron + "]")
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void aScheduleThatNeverRunsIsRefused() {
        assertThatThrownBy(() -> schedule.validate("0 0 9 30 2 *", "UTC", NOW))   // the 30th of February
                .isInstanceOf(BadRequestException.class).hasMessageContaining("never runs");
    }

    @Test
    void aScheduleThatRunsTooOftenIsRefusedEvenIfOnlySometimes() {
        for (String cron : new String[]{"* * * * * *", "*/5 * * * * *", "0 * * * * *", "0 */10 * * * *", "0 */59 * * * *",
                "0 0,5 9 * * *", "0 0 8-10 * * *" /* hourly: allowed */}) {
            if (cron.equals("0 0 8-10 * * *")) {
                assertThatCode(() -> schedule.validate(cron, "UTC", NOW)).as(cron).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> schedule.validate(cron, "UTC", NOW)).as(cron)
                        .isInstanceOf(BadRequestException.class).hasMessageContaining("too often");
            }
        }
    }

    @Test
    void theMinimumGapComesFromConfiguration() {
        HandOfflyProperties relaxed = new HandOfflyProperties();
        relaxed.getJobs().setMinIntervalMinutes(5);
        assertThatCode(() -> new JobSchedule(relaxed).validate("0 */10 * * * *", "UTC", NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new JobSchedule(relaxed).validate("0 */2 * * * *", "UTC", NOW)).isInstanceOf(BadRequestException.class);
        assertThat(Duration.ofMinutes(relaxed.getJobs().getMinIntervalMinutes())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void anUnknownTimezoneIsRefused() {
        for (String zone : new String[]{null, "", "Mars/Olympus", "IST9", "UTC+5:30", "../../etc", "Asia/Kolkata; x"}) {
            assertThatThrownBy(() -> schedule.validate("0 0 9 * * *", zone, NOW)).as("[" + zone + "]")
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("timezone");
        }
        assertThatCode(() -> schedule.validate("0 0 9 * * *", "Europe/London", NOW)).doesNotThrowAnyException();
    }
}
