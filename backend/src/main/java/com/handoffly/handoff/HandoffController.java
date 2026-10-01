package com.handoffly.handoff;

import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.auth.UserPrincipal;
import com.handoffly.common.web.PageResponse;
import com.handoffly.handoff.dto.CreateHandoffRequest;
import com.handoffly.handoff.dto.DashboardResponse;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffSummaryResponse;
import com.handoffly.handoff.dto.ReasonRequest;
import com.handoffly.handoff.dto.ReplaceItemsRequest;
import com.handoffly.handoff.dto.UpdateHandoffRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/handoffs")
public class HandoffController {

    private final HandoffService handoffService;

    public HandoffController(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffDetailResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                        @Valid @RequestBody CreateHandoffRequest request) {
        return handoffService.create(principal.id(), request);
    }

    @GetMapping
    public PageResponse<HandoffSummaryResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) List<HandoffStatus> status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return handoffService.list(principal.id(), status, q, pageable);
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return handoffService.dashboard(principal.id());
    }

    @GetMapping("/{id}")
    public HandoffDetailResponse get(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable Long id) {
        return handoffService.getDetail(principal.id(), id);
    }

    @GetMapping("/{id}/events")
    public List<AuditEventResponse> events(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id) {
        return handoffService.getEvents(principal.id(), id);
    }

    @PatchMapping("/{id}")
    public HandoffDetailResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id,
                                        @Valid @RequestBody UpdateHandoffRequest request) {
        return handoffService.update(principal.id(), id, request);
    }

    @PutMapping("/{id}/items")
    public HandoffDetailResponse replaceItems(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id,
                                              @Valid @RequestBody ReplaceItemsRequest request) {
        return handoffService.replaceItems(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        handoffService.deleteDraft(principal.id(), id);
    }

    @PostMapping("/{id}/submit")
    public HandoffDetailResponse submit(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id) {
        return handoffService.submit(principal.id(), id);
    }

    @PostMapping("/{id}/resend-link")
    public HandoffDetailResponse resendLink(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable Long id) {
        return handoffService.resendLink(principal.id(), id);
    }

    @PostMapping("/{id}/cancel")
    public HandoffDetailResponse cancel(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id,
                                        @Valid @RequestBody(required = false) ReasonRequest request) {
        return handoffService.cancel(principal.id(), id, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/dispute")
    public HandoffDetailResponse dispute(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable Long id,
                                         @Valid @RequestBody(required = false) ReasonRequest request) {
        return handoffService.dispute(principal.id(), id, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/close")
    public HandoffDetailResponse close(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long id,
                                       @Valid @RequestBody(required = false) ReasonRequest request) {
        return handoffService.close(principal.id(), id, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/request-missing-confirmation")
    public HandoffDetailResponse requestMissingConfirmation(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long id) {
        return handoffService.requestMissingConfirmation(principal.id(), id);
    }
}
