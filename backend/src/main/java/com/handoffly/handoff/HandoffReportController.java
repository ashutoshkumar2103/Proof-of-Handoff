package com.handoffly.handoff;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.handoff.dto.HandoffReportResponse;
import com.handoffly.handoff.dto.ReportAnswerResponse;
import com.handoffly.handoff.dto.ReportQuestionRequest;
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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ReportAssistantService assistant;

    public HandoffReportController(HandoffReportService reports, ReportAssistantService assistant) {
        this.reports = reports;
        this.assistant = assistant;
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

    /** The ready-made questions of the Report Assistant. Plans that include HandoffCheck only; no AI is used. */
    @GetMapping("/assistant/suggestions")
    public List<ReportAnswerResponse.Suggestion> suggestions(@AuthenticationPrincipal UserPrincipal principal) {
        return assistant.suggestions(principal.id());
    }

    /**
     * A question about the customer's handoffs, read-only and answered from the report's own figures: all of the handoffs unless the question
     * names a period. Plans that include HandoffCheck only. An AI that is not available is an answer of kind UNAVAILABLE, not an error.
     */
    @PostMapping("/assistant/ask")
    public ReportAnswerResponse ask(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ReportQuestionRequest request) {
        return assistant.ask(principal.id(), request.timezone(), request.question(), request.suggestion());
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
