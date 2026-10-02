package com.handoffly.support;

import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.CustomerProfileResponse;
import com.handoffly.support.dto.CustomerSummaryResponse;
import com.handoffly.support.dto.PlanChangeRequest;
import com.handoffly.support.dto.PrefixRequest;
import com.handoffly.support.dto.ReplyRequest;
import com.handoffly.support.dto.StatusRequest;
import com.handoffly.support.dto.SupportAuditEventResponse;
import com.handoffly.support.dto.SupportDashboardResponse;
import com.handoffly.support.dto.TicketDetailResponse;
import com.handoffly.support.dto.TicketSummaryResponse;
import com.handoffly.support.staff.StaffPrincipal;
import com.handoffly.support.staff.SupportPermission;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The API behind the support portal. It sits behind the support security chain: only an active staff member
 * (a separate identity from customers, checked against the database on every request) gets in; a customer
 * token does not. Beyond that, each endpoint asks for one {@link SupportPermission}, and which roles hold it is
 * decided in one place ({@code SupportRole}) — so a ticket agent cannot reach customer administration and only
 * an admin reaches staff management or the full audit trail. There is deliberately no way here to touch a
 * customer's password or login, or to read or edit their handoffs.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportTicketService ticketService;
    private final SupportCustomerService customerService;
    private final SupportAuditService auditService;

    public SupportController(SupportTicketService ticketService, SupportCustomerService customerService,
                             SupportAuditService auditService) {
        this.ticketService = ticketService;
        this.customerService = customerService;
        this.auditService = auditService;
    }

    @PreAuthorize("hasAuthority('VIEW_DASHBOARD')")
    @GetMapping("/dashboard")
    public SupportDashboardResponse dashboard(@AuthenticationPrincipal StaffPrincipal principal) {
        // Customer metrics (priority customers) only for staff who may see customers; ticket agents get ticket counts.
        return ticketService.dashboard(principal.can(SupportPermission.VIEW_CUSTOMERS));
    }

    // ------------------------------------------------------------ Customers

    @PreAuthorize("hasAuthority('VIEW_CUSTOMERS')")
    @GetMapping("/customers")
    public PageResponse<CustomerSummaryResponse> customers(@RequestParam(name = "q", required = false) String query,
                                                           @PageableDefault(size = 20) Pageable pageable) {
        return customerService.search(query, pageable);
    }

    @PreAuthorize("hasAuthority('VIEW_CUSTOMERS')")
    @GetMapping("/customers/{accountCode}")
    public CustomerProfileResponse customer(@PathVariable String accountCode) {
        return customerService.profile(accountCode);
    }

    @PreAuthorize("hasAuthority('MANAGE_CUSTOMERS')")
    @PutMapping("/customers/{accountCode}/prefix")
    public CustomerProfileResponse changePrefix(@AuthenticationPrincipal StaffPrincipal principal,
                                                @PathVariable String accountCode,
                                                @Valid @RequestBody PrefixRequest request) {
        return customerService.changePrefix(principal.id(), accountCode, request.prefix());
    }

    @PreAuthorize("hasAuthority('MANAGE_CUSTOMERS')")
    @PutMapping("/customers/{accountCode}/plan")
    public CustomerProfileResponse changePlan(@AuthenticationPrincipal StaffPrincipal principal,
                                              @PathVariable String accountCode,
                                              @Valid @RequestBody PlanChangeRequest request) {
        return customerService.changePlan(principal.id(), accountCode, request);
    }

    // ------------------------------------------------------------ Tickets

    @PreAuthorize("hasAuthority('WORK_TICKETS')")
    @GetMapping("/tickets")
    public PageResponse<TicketSummaryResponse> tickets(
            @RequestParam(name = "status", required = false) List<TicketStatus> status,
            @RequestParam(name = "priorityOnly", defaultValue = "false") boolean priorityOnly,
            @RequestParam(name = "accountCode", required = false) String accountCode,
            @PageableDefault(size = 20) Pageable pageable) {
        return ticketService.search(status, priorityOnly, accountCode, pageable);
    }

    @PreAuthorize("hasAuthority('WORK_TICKETS')")
    @GetMapping("/tickets/{ticketCode}")
    public TicketDetailResponse ticket(@PathVariable String ticketCode) {
        return ticketService.getForSupport(ticketCode);
    }

    @PreAuthorize("hasAuthority('WORK_TICKETS')")
    @PutMapping("/tickets/{ticketCode}/status")
    public TicketDetailResponse changeStatus(@PathVariable String ticketCode,
                                             @Valid @RequestBody StatusRequest request) {
        return ticketService.changeStatus(ticketCode, request.status());
    }

    @PreAuthorize("hasAuthority('WORK_TICKETS')")
    @PostMapping("/tickets/{ticketCode}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetailResponse reply(@AuthenticationPrincipal StaffPrincipal principal,
                                      @PathVariable String ticketCode,
                                      @Valid @RequestBody ReplyRequest request) {
        return ticketService.replyAsSupport(principal.id(), ticketCode, request.body());
    }

    @PreAuthorize("hasAuthority('WORK_TICKETS')")
    @GetMapping("/tickets/{ticketCode}/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> download(@PathVariable String ticketCode, @PathVariable Long attachmentId) {
        return TicketController.toDownloadResponse(ticketService.downloadForSupport(ticketCode, attachmentId));
    }

    // ------------------------------------------------------------ Audit

    /** The whole support audit trail, newest first. */
    @PreAuthorize("hasAuthority('VIEW_AUDIT')")
    @GetMapping("/audit")
    public PageResponse<SupportAuditEventResponse> audit(@PageableDefault(size = 20) Pageable pageable) {
        return auditService.trail(pageable);
    }
}
