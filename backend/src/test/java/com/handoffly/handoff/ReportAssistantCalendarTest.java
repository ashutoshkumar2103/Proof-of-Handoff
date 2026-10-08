package com.handoffly.handoff;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The AI is told the days of "this month", "last week" and so on, worked out here, so that it does no date arithmetic of its own. */
class ReportAssistantCalendarTest {

    @Test
    void theNamedPeriodsAreWorkedOutFromToday() {
        // Friday 9 October 2026
        assertThat(ReportAssistantService.calendar(LocalDate.of(2026, 10, 9))).isEqualTo(
                "Today is 2026-10-09. This week is 2026-10-05 to 2026-10-11 (Monday to Sunday); last week is 2026-09-28 to 2026-10-04. "
                        + "This month is 2026-10-01 to 2026-10-31; last month is 2026-09-01 to 2026-09-30. "
                        + "This year is 2026-01-01 to 2026-12-31; last year is 2025-01-01 to 2025-12-31.");
    }

    @Test
    void monthsAndYearsThatRollOverAreRight() {
        // 1 January 2027 (a Friday): last month is December, last week crosses the new year, February of a leap year has 29 days
        assertThat(ReportAssistantService.calendar(LocalDate.of(2027, 1, 1)))
                .contains("This week is 2026-12-28 to 2027-01-03").contains("last week is 2026-12-21 to 2026-12-27")
                .contains("This month is 2027-01-01 to 2027-01-31; last month is 2026-12-01 to 2026-12-31")
                .contains("This year is 2027-01-01 to 2027-12-31; last year is 2026-01-01 to 2026-12-31");
        assertThat(ReportAssistantService.calendar(LocalDate.of(2028, 2, 15))).contains("This month is 2028-02-01 to 2028-02-29");
    }
}
