package com.handoffly.support.staff;

import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.ChangeStaffActiveRequest;
import com.handoffly.support.dto.ChangeStaffRoleRequest;
import com.handoffly.support.dto.CreateStaffRequest;
import com.handoffly.support.dto.StaffSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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

/** Staff management — administrators only (the MANAGE_STAFF permission, which only the ADMIN role holds). */
@RestController
@RequestMapping("/api/v1/support/staff")
@PreAuthorize("hasAuthority('MANAGE_STAFF')")
public class StaffManagementController {

    private final StaffManagementService service;

    public StaffManagementController(StaffManagementService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<StaffSummaryResponse> list(@RequestParam(name = "q", required = false) String text,
                                                   @RequestParam(name = "role", required = false) SupportRole role,
                                                   @RequestParam(name = "active", required = false) Boolean active,
                                                   @PageableDefault(size = 20) Pageable pageable) {
        return service.list(text, role, active, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffSummaryResponse create(@AuthenticationPrincipal StaffPrincipal principal,
                                       @Valid @RequestBody CreateStaffRequest request) {
        return service.create(principal.id(), request);
    }

    @PutMapping("/{staffCode}/role")
    public StaffSummaryResponse changeRole(@AuthenticationPrincipal StaffPrincipal principal,
                                           @PathVariable String staffCode,
                                           @Valid @RequestBody ChangeStaffRoleRequest request) {
        return service.changeRole(principal.id(), staffCode, request.fromRole(), request.toRole(), request.reason());
    }

    @PutMapping("/{staffCode}/active")
    public StaffSummaryResponse setActive(@AuthenticationPrincipal StaffPrincipal principal,
                                          @PathVariable String staffCode,
                                          @Valid @RequestBody ChangeStaffActiveRequest request) {
        return service.setActive(principal.id(), staffCode, request.active(), request.reason());
    }
}
