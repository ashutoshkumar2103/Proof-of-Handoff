package com.handoffly.returns;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.returns.dto.CreateReturnRequest;
import com.handoffly.returns.dto.ReturnEventResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-facing return endpoints, nested under a handoff so returns always live on the
 * same handoff record.
 */
@RestController
@RequestMapping("/api/v1/handoffs/{handoffId}/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnEventResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable Long handoffId,
                                      @Valid @RequestBody CreateReturnRequest request) {
        return returnService.createByOwner(principal.id(), handoffId, request);
    }

    @PostMapping("/{returnId}/confirm")
    public ReturnEventResponse confirm(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long handoffId,
                                       @PathVariable Long returnId) {
        return returnService.confirm(principal.id(), handoffId, returnId);
    }
}
