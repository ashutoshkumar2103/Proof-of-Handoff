package com.handoffly.documentcheck;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
