package com.handoffly.support;

import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.CustomerProfileResponse;
import com.handoffly.support.dto.CustomerSummaryResponse;
import com.handoffly.support.dto.PlanChangeRequest;
import com.handoffly.support.dto.SubscriptionHistoryResponse;
import com.handoffly.support.staff.SupportStaff;
import com.handoffly.support.staff.SupportStaffService;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.user.SubscriptionHistory;
import com.handoffly.user.SubscriptionSummary;
import com.handoffly.user.SupportEntitlements;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Moves a customer to another plan — or puts one who has none on a plan — starting now, paid for until a last day if
     * one is named and otherwise for the plan's own duration, or, on the plan they already have, changes how long it is
     * paid for; what they may use follows automatically. This is an operational
     * change by a named staff member — it does not claim that a payment happened. It is recorded in the audit trail
     * and appended to the customer's subscription history, in the same transaction.
     */
    @Transactional
    public CustomerProfileResponse changePlan(Long staffId, String accountCode, PlanChangeRequest request) {
        SupportStaff staff = staffService.getById(staffId);
        if (request.validUntil() != null && request.validUntil().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new BadRequestException("The last day of the plan cannot be in the past.");
        }
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        Instant validUntil = endOfDay(request.validUntil());
        UserService.PlanChange change = userService.changeSubscriptionPlan(
                accountCode, request.fromPlan(), request.toPlan(), validUntil, staffId, reason);
        if (change.previousPlan() == request.toPlan()) {
            audit.recordCustomerChange(staff, change.customer(), SupportAuditEventType.SUBSCRIPTION_PERIOD_CHANGED,
                    lastDay(change.previousValidUntil()), lastDay(validUntil), reason);
        } else {
            audit.recordCustomerChange(staff, change.customer(), SupportAuditEventType.PLAN_CHANGED,
                    change.previousPlan() == null ? null : change.previousPlan().name(), request.toPlan().name(), reason);
        }
        return profileOf(change.customer());
    }

    /** The plan is paid for through the whole of its last day, i.e. until the start of the next (UTC). */
    private static Instant endOfDay(LocalDate lastDay) {
        return lastDay == null ? null : lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** For the audit trail: the last day a plan was paid for, or "none" for no end date. */
    private static String lastDay(Instant validUntil) {
        return validUntil == null ? "none" : LocalDate.ofInstant(validUntil.minusSeconds(1), ZoneOffset.UTC).toString();
    }

    private void record(SupportStaff staff, UserService.AccountChange change, SupportAuditEventType type, String reason) {
        audit.recordCustomerChange(staff, change.customer(), type, change.previous(), change.current(), reason);
    }

    private CustomerProfileResponse profileOf(User customer) {
        return new CustomerProfileResponse(
                CustomerSummaryResponse.from(customer),
                SubscriptionSummary.of(customer, Instant.now()),
                // What the plan includes only counts while the subscription is active; no plan gets the activation help.
                SupportEntitlements.of(customer, null),
                customer.peekNextHandoffReference(),
                ticketService.countActiveForAccount(customer.getId()),
                ticketService.recentForAccount(customer.getId()),
                audit.recentForCustomer(customer.getId()),
                subscriptionHistory(customer.getId()));
    }

    /** The customer's plans, newest first, with the staff member behind each change that support made. */
    private List<SubscriptionHistoryResponse> subscriptionHistory(Long customerId) {
        Map<Long, SupportStaff> staffById = new HashMap<>();
        return userService.subscriptionHistory(customerId).stream().map((SubscriptionHistory h) -> {
            SupportStaff by = h.getStaffId() == null ? null
                    : staffById.computeIfAbsent(h.getStaffId(), staffService::getById);
            return new SubscriptionHistoryResponse(h.getPreviousPlan(), h.getNewPlan(), h.getStartsAt(),
                    h.getValidUntil(), h.getCreatedAt(), h.getSource(),
                    by == null ? null : by.getStaffCode(), by == null ? null : by.getName(), h.getReason());
        }).toList();
    }
}
