package com.handoffly.handoff.dto;

import com.handoffly.common.web.PageResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The handoff report for one customer and one period: which days it covers, the totals for every handoff created in
 * them, and one page of those handoffs (the same compact rows the Dashboard list uses, so a figure means the same in both).
 */
public record HandoffReportResponse(Period period, Summary summary, PageResponse<HandoffSummaryResponse> handoffs) {

    /**
     * The window that was applied: whole calendar days, first and last both included, in {@code timezone}, matched against
     * the date each handoff was created. Echoed back so the page can say exactly what it is showing.
     */
    public record Period(LocalDate from, LocalDate to, String timezone) {}

    /**
     * Totals over every handoff created in the period, whatever its status. {@code open} and {@code overdue} are as of
     * now, not as of the end of the period. The item figures count handoffs that were sent (a draft has given nothing),
     * using the same per-handoff figures as the rows.
     *
     * <p>Every item that was given is either back ({@code itemsReturned}), reported missing ({@code itemsMissing}) or neither —
     * the handoff's "remaining". That last part is split by where it stayed so nothing has to be hunted for:
     * on a handoff the recipient rejected ({@code itemsRejected}), on one that was cancelled ({@code itemsCancelled}), or on any
     * other sent handoff, i.e. not back yet ({@code itemsStillOut}). So
     * {@code itemsGiven = itemsReturned + itemsMissing + itemsStillOut + itemsRejected + itemsCancelled}.
     */
    public record Summary(long created, long closed, long open, long overdue,
                          BigDecimal itemsGiven, BigDecimal itemsReturned, BigDecimal itemsMissing,
                          BigDecimal itemsStillOut, BigDecimal itemsRejected, BigDecimal itemsCancelled) {}
}
