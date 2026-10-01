package com.handoffly.documentcheck;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.documentcheck.dto.ReturnImportResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/handoff-check")
public class DocumentCheckController {

    private final DocumentCheckService documentCheckService;

    public DocumentCheckController(DocumentCheckService documentCheckService) {
        this.documentCheckService = documentCheckService;
    }

    @PostMapping
    public CompareResult compare(@AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody CompareRequest request) {
        return documentCheckService.compare(principal.id(), request);
    }

    /** Standalone mode, step 1: read one file into editable item/quantity lines. Nothing is stored. */
    @PostMapping(path = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DocumentLine> extract(@RequestParam MultipartFile file) {
        return documentCheckService.extractLines(file);
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
