package com.handoffly.support;

import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.SupportAuditEventResponse;
import com.handoffly.support.staff.SupportStaff;
import com.handoffly.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The one place support actions are written to, and the whole trail read from, the audit log. Recording joins
 * the caller's transaction, so a change and its record stand or fall together.
 */
@Service
public class SupportAuditService {

    private final SupportAuditEventRepository events;

    public SupportAuditService(SupportAuditEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCustomerChange(SupportStaff actor, User customer, SupportAuditEventType type,
                                     String previous, String current, String reason) {
        events.save(SupportAuditEvent.ofCustomerChange(actor, customer, type, previous, current, reason));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStaffChange(SupportStaff actor, SupportStaff target, SupportAuditEventType type,
                                  String previous, String current, String reason) {
        events.save(SupportAuditEvent.ofStaffChange(actor, target, type, previous, current, reason));
    }

    /** A customer's latest changes, for their profile. */
    @Transactional(readOnly = true)
    public List<SupportAuditEventResponse> recentForCustomer(Long customerId) {
        return events.findTop10ByCustomerIdOrderByIdDesc(customerId).stream().map(SupportAuditEventResponse::from).toList();
    }

    /** The whole trail, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<SupportAuditEventResponse> trail(Pageable requested) {
        Pageable page = PageRequest.of(requested.getPageNumber(), Math.min(requested.getPageSize(), Paging.MAX_PAGE_SIZE));
        return PageResponse.of(events.findAllByOrderByIdDesc(page), SupportAuditEventResponse::from);
    }
}
