package com.handoffly.support.dto;

/**
 * The support dashboard tiles. {@code priorityCustomers} counts customers on the priority plan who still
 * have a ticket that needs attention; it is a customer metric, so it is {@code null} (absent) for staff who
 * may not see customers.
 */
public record SupportDashboardResponse(
        long open,
        long inProgress,
        long waitingForCustomer,
        Long priorityCustomers
) {}
