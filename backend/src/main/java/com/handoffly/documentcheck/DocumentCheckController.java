package com.handoffly.documentcheck;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.documentcheck.dto.AiItemMatchRequest;
import com.handoffly.documentcheck.dto.AiItemMatchResponse;
import com.handoffly.documentcheck.dto.AiMappingResponse;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.ComparisonExportRequest;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.documentcheck.dto.ItemImportPreview;
import com.handoffly.documentcheck.dto.ReturnImportResult;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * HandoffCheck, for the signed-in customer. Every endpoint here needs an active subscription. The HandoffCheck tool itself —
 * comparing, reading a file, exporting, comparing two files in one step — is also a feature of some plans only
 * (Half-Yearly and Yearly). Two endpoints are not that tool: importing items into a new handoff and importing a returns
 * file belong to New handoff and Returns and merely reuse its file reading, so they stay on every plan. {@link #requireAccess}
 * enforces all of this once, for every endpoint here and for any added later (which is the tool unless named below).
 */
@RestController
@RequestMapping(DocumentCheckController.BASE)
public class DocumentCheckController {

    static final String BASE = "/api/v1/handoff-check";

    /** The endpoints that are New handoff and Returns, not the HandoffCheck tool: every plan keeps them (see the class note). */
    private static final Set<String> ON_EVERY_PLAN = Set.of(BASE + "/import-items", BASE + "/return-import");

    private final DocumentCheckService documentCheckService;
    private final AiMappingService aiMappingService;
    private final ComparisonExportService exportService;
    private final UserService users;

    public DocumentCheckController(DocumentCheckService documentCheckService, AiMappingService aiMappingService,
                                   ComparisonExportService exportService, UserService users) {
        this.documentCheckService = documentCheckService;
        this.aiMappingService = aiMappingService;
        this.exportService = exportService;
        this.users = users;
    }

    /**
     * Runs before every endpoint of this controller, ahead of reading the request body or a file, so whatever is sent the
     * customer gets the same answer: a subscription that has ended is refused first ({@code 403 subscription_expired}), then —
     * for the HandoffCheck tool, which is every endpoint except the two named in {@link #ON_EVERY_PLAN} — a plan that does not
     * include it ({@code 403 plan_required}). The customer and the clock are read afresh each time, so no earlier answer, and
     * nothing the client remembers, can let a request through.
     */
    @ModelAttribute
    void requireAccess(@AuthenticationPrincipal UserPrincipal principal, HttpServletRequest request) {
        User customer = users.getById(principal.id());
        users.requireActiveSubscription(customer);
        String endpoint = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if (!ON_EVERY_PLAN.contains(endpoint)) {
            users.requireHandoffCheckPlan(customer);
        }
    }

    @PostMapping
    public CompareResult compare(@AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody CompareRequest request) {
        return documentCheckService.compare(principal.id(), request);
    }

    /**
     * Standalone mode, step 1: read one file into editable item/quantity lines. Nothing is stored. The columns can be named
     * ({@code itemColumn}, {@code quantityColumn}, optionally {@code headerRow}) when the customer chose them, for example by accepting
     * an AI Assist suggestion; without them the file is read exactly as before.
     */
    @PostMapping(path = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DocumentLine> extract(@RequestParam MultipartFile file,
                                      @RequestParam(required = false) Integer itemColumn,
                                      @RequestParam(required = false) Integer quantityColumn,
                                      @RequestParam(required = false) Integer headerRow) {
        return documentCheckService.extractLines(file, itemColumn, quantityColumn, headerRow);
    }

    /**
     * AI Assist (optional): suggests which columns of a spreadsheet hold the item and the quantity, for the customer to review.
     * Part of HandoffCheck, so {@link #requireAccess} gates it like every other endpoint here. Only a suggestion: it returns no lines and
     * changes nothing, and when AI is not available the answer says so (still 200) so HandoffCheck carries on as normal.
     */
    @PostMapping(path = "/ai/column-mapping", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiMappingResponse suggestColumns(@AuthenticationPrincipal UserPrincipal principal, @RequestParam MultipartFile file) {
        return aiMappingService.suggestColumns(principal.id(), file);
    }

    /**
     * AI Assist (optional): suggests which item names of the two files are probably the same item spelled differently, for the customer to
     * accept or reject. Given the names only, in one request. Only a suggestion: nothing is compared or changed, and when AI is not available
     * the answer says so (still 200) so HandoffCheck carries on as normal. Part of HandoffCheck, so {@link #requireAccess} gates it.
     */
    @PostMapping("/ai/item-matching")
    public AiItemMatchResponse suggestItemMatches(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AiItemMatchRequest request) {
        return aiMappingService.suggestItemMatches(principal.id(), request.fileA(), request.fileB());
    }

    /**
     * A finished comparison as a PDF or CSV ({@code format=PDF|CSV}). The comparison is run again from the same
     * request, so the file always agrees with what was shown; nothing is stored, and no handoff or return is touched.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal UserPrincipal principal,
                                         @RequestParam ComparisonExportFormat format,
                                         @Valid @RequestBody ComparisonExportRequest request) {
        CompareResult result = documentCheckService.compare(principal.id(), request.comparison());
        ComparisonExportService.ExportedFile file = exportService.export(
                result, request.fileAName(), request.fileBName(), format, Instant.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }

    /**
     * New Handoff: read an item list (CSV or Excel) into rows to review before they are added to the item table.
     * Nothing is stored and no handoff is created or changed.
     */
    @PostMapping(path = "/import-items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ItemImportPreview importItems(@RequestParam MultipartFile file) {
        return documentCheckService.previewItemImport(file);
    }

    /** Standalone mode, one shot: extract two files and compare them. Nothing is stored. */
    @PostMapping(path = "/compare-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompareResult compareFiles(@RequestParam MultipartFile fileA, @RequestParam MultipartFile fileB) {
        return documentCheckService.compareFiles(fileA, fileB);
    }

    /** Return-import mode: extract quantities from a file and match them to a handoff's items. */
    @PostMapping(path = "/return-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReturnImportResult returnImport(@AuthenticationPrincipal UserPrincipal principal,
                                           @RequestParam Long handoffId,
                                           @RequestParam MultipartFile file) {
        return documentCheckService.matchReturnImport(principal.id(), handoffId, file);
    }
}
