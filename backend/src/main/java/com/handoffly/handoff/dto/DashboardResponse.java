package com.handoffly.handoff.dto;

import com.handoffly.handoff.HandoffStatus;

import java.util.Map;

/**
 * Dashboard tallies: how many handoffs sit in each status, plus a derived overdue
 * count and the overall total, for the owner's at-a-glance view.
 */
public record DashboardResponse(
        Map<HandoffStatus, Long> statusCounts,
        long overdueCount,
        long total
) {}
