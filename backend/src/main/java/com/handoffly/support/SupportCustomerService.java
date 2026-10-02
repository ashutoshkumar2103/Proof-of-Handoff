package com.handoffly.support;

import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.CustomerProfileResponse;
import com.handoffly.support.dto.CustomerSummaryResponse;
import com.handoffly.support.dto.PlanChangeRequest;
import com.handoffly.support.staff.SupportStaff;
import com.handoffly.support.staff.SupportStaffService;
import com.handoffly.user.SupportEntitlements;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What support may see and do about a customer: find them, read their profile, change their plan and
 * set the prefix of their handoff references. Every change is recorded in the audit trail in the same
 * transaction as the change itself. Nothing else about an account — its password, its login, any
 * handoff — is reachable from here.
 */
@Service
public class SupportCustomerService {

    private static final Sort BY_ACCOUNT_ID = Sort.by("accountCode");

    private final UserService userService;
    private final SupportTicketService ticketService;
    private final SupportStaffService staffService;
    private final SupportAuditService audit;

    public SupportCustomerService(UserService userService, SupportTicketService ticketService,
                                  SupportStaffService staffService, SupportAuditService audit) {
        this.userService = userService;
        this.ticketService = ticketService;
        this.staffService = staffService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerSummaryResponse> search(String query, Pageable pageable) {
        return PageResponse.of(
                userService.searchCustomers(query, Paging.of(pageable, BY_ACCOUNT_ID)),
                CustomerSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerProfileResponse profile(String accountCode) {
        return profileOf(userService.getCustomerByAccountCode(accountCode));
    }

    @Transactional
    public CustomerProfileResponse changePrefix(Long staffId, String accountCode, String prefix) {
        SupportStaff staff = staffService.getById(staffId);
        UserService.AccountChange change = userService.changeHandoffPrefix(accountCode, prefix);
        if (!change.previous().equals(change.current())) {   // saying the same prefix again is not a change
            record(staff, change, SupportAuditEventType.HANDOFF_PREFIX_CHANGED, null);
        }
        return profileOf(change.customer());
    }

    /**
     * Moves a customer to another plan; what they may use follows automatically. This is an operational
     * change by a named staff member — it does not claim that a payment happened.
     */
    @Transactional
    public CustomerProfileResponse changePlan(Long staffId, String accountCode, PlanChangeRequest request) {
        SupportStaff staff = staffService.getById(staffId);
        UserService.AccountChange change =
                userService.changeSubscriptionPlan(accountCode, request.fromPlan(), request.toPlan());
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        record(staff, change, SupportAuditEventType.PLAN_CHANGED, reason);
        return profileOf(change.customer());
    }

    private void record(SupportStaff staff, UserService.AccountChange change, SupportAuditEventType type, String reason) {
        audit.recordCustomerChange(staff, change.customer(), type, change.previous(), change.current(), reason);
    }

    private CustomerProfileResponse profileOf(User customer) {
        return new CustomerProfileResponse(
                CustomerSummaryResponse.from(customer),
                SupportEntitlements.of(customer.getSubscriptionPlan(), null),
                customer.peekNextHandoffReference(),
                ticketService.countActiveForAccount(customer.getId()),
                ticketService.recentForAccount(customer.getId()),
                audit.recentForCustomer(customer.getId()));
    }
}
