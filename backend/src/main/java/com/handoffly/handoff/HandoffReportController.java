package com.handoffly.handoff;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.handoff.dto.HandoffReportResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports for the signed-in customer. Read-only; the customer is always the authenticated one (there is no way to name
 * another), and the period is {@code from}..{@code to} as whole days (both included) in {@code timezone}.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class HandoffReportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final HandoffReportService reports;

    public HandoffReportController(HandoffReportService reports) {
        this.reports = reports;
    }

    /** The totals for the period, and one page of its handoffs (optionally narrowed by status or to the overdue ones). */
    @GetMapping("/handoffs")
    public HandoffReportResponse handoffs(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String timezone,
            @RequestParam(required = false) List<HandoffStatus> status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @PageableDefault(size = 20, sort = "created") Pageable pageable) {
        return reports.report(principal.id(), new HandoffReportService.Query(from, to, timezone, status, overdue), pageable);
    }

    /** Every handoff that matches the same filters, as a CSV file — not just the page on screen. */
    @GetMapping("/handoffs/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String timezone,
            @RequestParam(required = false) List<HandoffStatus> status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @SortDefault("created") Sort sort) {
        HandoffReportService.CsvFile file =
                reports.csv(principal.id(), new HandoffReportService.Query(from, to, timezone, status, overdue), sort);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(CSV)
                .contentLength(file.content().length)
                .body(file.content());
    }
}
