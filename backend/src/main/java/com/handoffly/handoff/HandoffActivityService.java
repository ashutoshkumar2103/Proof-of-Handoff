package com.handoffly.handoff;

import com.handoffly.audit.AuditEvent;
import com.handoffly.audit.AuditEventRepository;
import com.handoffly.audit.AuditEventType;
import com.handoffly.returns.ReturnLineRepository;
import com.handoffly.returns.ReturnQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only views of ONE customer's own handoffs, for the reminders and the weekly summary: which ones are due soon,
 * overdue, or have items missing, and what happened over a period. Every method takes the owner and only ever looks
 * at that owner's handoffs. It decides nothing new: "overdue" is {@link HandoffMapper#isOverdue}, "missing" and
 * "returned" come from {@link ReturnQueryService}, and a handoff that is closed, cancelled or rejected is never
 * "open" — so a handoff force-closed with items lost is not reminded about again.
 */
@Service
@Transactional(readOnly = true)
public class HandoffActivityService {

    /** A handoff a reminder mentions: who it is for, when it is due, and how much has not come back. */
    public record ReminderLine(String reference, String title, String recipientName, Instant dueAt,
                               BigDecimal notReturned, BigDecimal missing) {}

    /** A handoff still waiting for its recipient to answer: who it is for and when it went out. */
    public record AwaitingLine(String reference, String title, String recipientName, Instant sentAt) {}

    /** A handoff named in the summary. */
    public record Ref(String reference, String title) {}

    /** The last stretch of a customer's handoff activity. */
    public record WeeklyActivity(List<Ref> created, List<Ref> closed, List<Ref> stillOpen,
                                 BigDecimal itemsGiven, BigDecimal itemsReturned, BigDecimal itemsMissing) {
        public boolean isEmpty() {
            return created.isEmpty() && closed.isEmpty() && stillOpen.isEmpty()
                    && itemsGiven.signum() == 0 && itemsReturned.signum() == 0 && itemsMissing.signum() == 0;
        }
    }

    /** Statuses in which items are out with the recipient and a return is still to come (and not yet overdue). */
    private static final Set<HandoffStatus> DUE_SOON_STATUSES = EnumSet.of(
            HandoffStatus.ACTIVE_WITH_RECIPIENT, HandoffStatus.RETURN_PENDING, HandoffStatus.PARTIALLY_RETURNED);
    /** Not a draft and not finished: the handoffs that are still "open" (also what the handoff report counts as open). */
    static final Set<HandoffStatus> OPEN_STATUSES = EnumSet.complementOf(EnumSet.of(
            HandoffStatus.DRAFT, HandoffStatus.CLOSED, HandoffStatus.REJECTED, HandoffStatus.CANCELLED));

    private final HandoffRepository handoffs;
    private final HandoffMapper mapper;
    private final ReturnQueryService returns;
    private final ReturnLineRepository returnLines;
    private final AuditEventRepository audit;

    public HandoffActivityService(HandoffRepository handoffs, HandoffMapper mapper, ReturnQueryService returns,
                                  ReturnLineRepository returnLines, AuditEventRepository audit) {
        this.handoffs = handoffs;
        this.mapper = mapper;
        this.returns = returns;
        this.returnLines = returnLines;
        this.audit = audit;
    }

    /**
     * Handoffs due back on one of the next {@code days} calendar days — tomorrow up to {@code days} days from today,
     * in {@code zone}. Today's and past dates are not "due soon" (past ones are overdue and have their own reminder).
     * Only handoffs that still have something to come back.
     */
    public List<ReminderLine> returnsDueSoon(Long ownerId, Instant now, ZoneId zone, int days) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return handoffs.findByOwnerIdAndStatusIn(ownerId, DUE_SOON_STATUSES).stream()
                .filter(h -> h.getDueAt() != null)
                .filter(h -> {
                    LocalDate due = h.getDueAt().atZone(zone).toLocalDate();
                    return due.isAfter(today) && !due.isAfter(today.plusDays(days));
                })
                .filter(this::notFullyReturned)
                .sorted(Comparator.comparing(Handoff::getDueAt).thenComparing(Handoff::getId))
                .map(this::line)
                .toList();
    }

    /**
     * Handoffs that went out at least {@code wait} ago and whose recipient has neither accepted nor declined: still
     * {@link HandoffStatus#AWAITING_RECIPIENT}, the one state in which the recipient is offered that choice. Once they answer
     * the status moves on (or the handoff is cancelled), so it drops out by itself. Oldest first; each handoff appears once.
     */
    public List<AwaitingLine> awaitingRecipient(Long ownerId, Instant now, Duration wait) {
        Instant sentBefore = now.minus(wait);
        return handoffs.findByOwnerIdAndStatusIn(ownerId, EnumSet.of(HandoffStatus.AWAITING_RECIPIENT)).stream()
                .filter(h -> h.getOutgoingAt() != null && !h.getOutgoingAt().isAfter(sentBefore))
                .sorted(Comparator.comparing(Handoff::getOutgoingAt).thenComparing(Handoff::getId))
                .map(h -> new AwaitingLine(h.getPublicCode(), h.getTitle(), h.getRecipientName(), h.getOutgoingAt()))
                .toList();
    }

    /** Open handoffs past their due date that have not been fully returned. */
    public List<ReminderLine> overdue(Long ownerId) {
        return handoffs.findByOwnerIdAndStatusIn(ownerId, OPEN_STATUSES).stream()
                .filter(mapper::isOverdue)
                .filter(this::notFullyReturned)
                .sorted(Comparator.comparing(Handoff::getDueAt).thenComparing(Handoff::getId))
                .map(this::line)
                .toList();
    }

    /** Open handoffs with items still marked missing — never one that has been closed. */
    public List<ReminderLine> withMissingItems(Long ownerId) {
        return handoffs.findByOwnerIdAndStatusIn(ownerId, OPEN_STATUSES).stream()
                .filter(h -> h.getStatus().isActiveWithRecipient())
                .filter(h -> returns.netMissingTotal(h).signum() > 0)
                .sorted(Comparator.comparing(Handoff::getId))
                .map(this::line)
                .toList();
    }

    /**
     * What happened in {@code [from, to)}: handoffs created, handoffs closed, how many are open now, the items that
     * went out and the items that came back in the period, and the items missing right now across open handoffs.
     */
    public WeeklyActivity activity(Long ownerId, Instant from, Instant to) {
        List<Ref> created = handoffs.findByOwnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(ownerId, from, to)
                .stream().sorted(Comparator.comparing(Handoff::getId)).map(HandoffActivityService::ref).toList();

        List<Handoff> open = handoffs.findByOwnerIdAndStatusIn(ownerId, OPEN_STATUSES).stream()
                .sorted(Comparator.comparing(Handoff::getId)).toList();

        List<Long> ownedIds = handoffs.findByOwnerIdAndStatusIn(ownerId, EnumSet.of(HandoffStatus.CLOSED)).stream()
                .map(Handoff::getId).toList();
        List<Ref> closed = ownedIds.isEmpty() ? List.of()
                : handoffs.findAllById(audit.findByHandoffIdInAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                                ownedIds, AuditEventType.HANDOFF_CLOSED, from, to).stream()
                        .map(AuditEvent::getHandoffId).distinct().toList())
                .stream().sorted(Comparator.comparing(Handoff::getId)).map(HandoffActivityService::ref).toList();

        BigDecimal given = handoffs.findByOwnerIdAndOutgoingAtGreaterThanEqualAndOutgoingAtLessThan(ownerId, from, to).stream()
                .flatMap(h -> h.getItems().stream()).map(HandoffItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returned = returnLines.sumReturnedForOwnerBetween(ownerId, ItemCondition.MISSING, from, to);
        BigDecimal missing = open.stream().map(returns::netMissingTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WeeklyActivity(created, closed, open.stream().map(HandoffActivityService::ref).toList(),
                given, returned, missing);
    }

    // ------------------------------------------------------------------ helpers

    private ReminderLine line(Handoff h) {
        return new ReminderLine(h.getPublicCode(), h.getTitle(), h.getRecipientName(), h.getDueAt(),
                notReturned(h), returns.netMissingTotal(h));
    }

    private static Ref ref(Handoff h) {
        return new Ref(h.getPublicCode(), h.getTitle());
    }

    private boolean notFullyReturned(Handoff h) {
        return notReturned(h).signum() > 0;
    }

    /** What has not come back: the quantity that went out minus what was returned (missing items count as not back). */
    private BigDecimal notReturned(Handoff h) {
        Map<Long, BigDecimal> returnedGood = returns.confirmedReturnedByItem(h.getId());
        BigDecimal total = BigDecimal.ZERO;
        for (HandoffItem item : h.getItems()) {
            BigDecimal owed = item.getQuantity().subtract(returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO));
            if (owed.signum() > 0) total = total.add(owed);
        }
        return total;
    }
}
