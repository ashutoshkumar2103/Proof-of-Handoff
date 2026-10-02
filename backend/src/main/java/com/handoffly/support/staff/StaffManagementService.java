package com.handoffly.support.staff;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.web.PageResponse;
import com.handoffly.support.Paging;
import com.handoffly.support.SupportAuditEventType;
import com.handoffly.support.SupportAuditService;
import com.handoffly.support.dto.CreateStaffRequest;
import com.handoffly.support.dto.StaffSummaryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an administrator does with the support team: list and search it, create staff, change roles,
 * deactivate and reactivate. All the rules live here; every change is audited in the same transaction.
 * Reaching this service at all requires the MANAGE_STAFF permission (checked at the API).
 * Administrator accounts are never created, re-roled or deactivated here — only ever managed at bootstrap —
 * so an admin cannot lock the team out by mistake.
 */
@Service
public class StaffManagementService {

    private static final Sort BY_STAFF_ID = Sort.by("staffCode");

    private final SupportStaffRepository staff;
    private final SupportStaffService staffService;
    private final SupportAuditService audit;

    public StaffManagementService(SupportStaffRepository staff, SupportStaffService staffService, SupportAuditService audit) {
        this.staff = staff;
        this.staffService = staffService;
        this.audit = audit;
    }

    /** Filtered in the database; each filter is optional. */
    @Transactional(readOnly = true)
    public PageResponse<StaffSummaryResponse> list(String text, SupportRole role, Boolean active, Pageable pageable) {
        Specification<SupportStaff> spec = Specification.allOf(
                StaffSpecifications.matching(text), StaffSpecifications.withRole(role), StaffSpecifications.isActive(active));
        return PageResponse.of(staff.findAll(spec, Paging.of(pageable, BY_STAFF_ID)), StaffSummaryResponse::from);
    }

    @Transactional
    public StaffSummaryResponse create(Long actorId, CreateStaffRequest request) {
        if (!request.role().isAssignable()) {
            throw new BadRequestException("Staff can be created as " + SupportRole.MANAGER + " or " + SupportRole.TICKET_AGENT + ".");
        }
        SupportStaff actor = staffService.getById(actorId);
        SupportStaff created = staffService.provision(request.name(), request.email(), request.password(), request.role());
        audit.recordStaffChange(actor, created, SupportAuditEventType.STAFF_CREATED, null, created.getRole().name(), null);
        return StaffSummaryResponse.from(created);
    }

    @Transactional
    public StaffSummaryResponse changeRole(Long actorId, String staffCode, SupportRole fromRole, SupportRole toRole, String reason) {
        if (!toRole.isAssignable()) {
            throw new BadRequestException("A role can be changed to " + SupportRole.MANAGER + " or " + SupportRole.TICKET_AGENT + ".");
        }
        SupportStaff actor = staffService.getById(actorId);
        SupportStaff target = lockedManageable(staffCode);
        if (target.getRole() != fromRole) {
            throw new ConflictException(staffCode + " is " + target.getRole() + ", not " + fromRole + ". Reload the page and try again.");
        }
        if (fromRole == toRole) {
            throw new ConflictException(staffCode + " already has the " + toRole + " role.");
        }
        target.changeRole(toRole);
        audit.recordStaffChange(actor, target, SupportAuditEventType.STAFF_ROLE_CHANGED, fromRole.name(), toRole.name(), clean(reason));
        return StaffSummaryResponse.from(target);
    }

    /** Deactivating takes effect at once: the support API re-checks "active" on every request. Records are never deleted. */
    @Transactional
    public StaffSummaryResponse setActive(Long actorId, String staffCode, boolean active, String reason) {
        SupportStaff actor = staffService.getById(actorId);
        SupportStaff target = lockedManageable(staffCode);
        if (target.isActive() == active) {
            throw new ConflictException(staffCode + " is already " + (active ? "active" : "inactive") + ".");
        }
        if (active) {
            target.reactivate();
        } else {
            target.deactivate();
        }
        audit.recordStaffChange(actor, target,
                active ? SupportAuditEventType.STAFF_REACTIVATED : SupportAuditEventType.STAFF_DEACTIVATED,
                active ? "INACTIVE" : "ACTIVE", active ? "ACTIVE" : "INACTIVE", clean(reason));
        return StaffSummaryResponse.from(target);
    }

    private SupportStaff lockedManageable(String staffCode) {
        SupportStaff target = staff.findByStaffCodeForUpdate(staffCode)
                .orElseThrow(() -> new NotFoundException("Staff member not found."));
        if (!target.getRole().isAssignable()) {
            throw new ConflictException("Administrator accounts cannot be changed here.");
        }
        return target;
    }

    private static String clean(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }
}
