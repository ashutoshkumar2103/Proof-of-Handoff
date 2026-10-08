package com.handoffly.handoff;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.util.Csv;
import com.handoffly.common.web.PageResponse;
import com.handoffly.handoff.dto.HandoffReportResponse;
import com.handoffly.handoff.dto.HandoffReportResponse.Period;
import com.handoffly.handoff.dto.HandoffReportResponse.Summary;
import com.handoffly.handoff.dto.HandoffSummaryResponse;
import com.handoffly.returns.ReturnQueryService;
import com.handoffly.user.UserService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The handoff report: how the handoffs ONE customer created in a period are doing. Read-only, and it works out nothing of
 * its own — every handoff's figures are {@link HandoffMapper#toSummary} (so a given, returned or missing quantity, and
 * "overdue", are exactly what the Dashboard and the handoff page show), fed with the return totals of the whole period
 * fetched at once by {@link ReturnQueryService} rather than one query per handoff. The totals, the filters, the sorting,
 * the page and the CSV are then plain operations on those same rows, so they cannot disagree with each other.
 *
 * <p>The period is matched against the date each handoff was <em>created</em> — the one date every handoff has, drafts
 * included — as whole calendar days in the time zone the caller names (UTC if none), and that is echoed back. Every
 * method takes the customer's id and finds handoffs only by it, and needs an active subscription like any other paid
 * part of the product ({@link UserService#requireActiveSubscription(Long)}).
 */
@Service
@Transactional(readOnly = true)
public class HandoffReportService {

    /** The most handoffs one report will read at once: protects the server, not a business rule. A longer period must be split. */
    static final int MAX_HANDOFFS = 10_000;

    /** The zone when none is named, as the region "UTC" so that it reads as UTC in the response and the CSV (the offset prints as "Z"). */
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Dates outside these are not a period anyone means, and are refused before they reach the database. */
    static final LocalDate EARLIEST = LocalDate.of(1970, 1, 1);
    static final LocalDate LATEST = LocalDate.of(9998, 12, 31);

    /** What the caller asked about: the days, the zone they are days in, and the optional narrowing of the table and the CSV. */
    public record Query(LocalDate from, LocalDate to, String timezone, List<HandoffStatus> statuses, boolean overdueOnly) {}

    /** A finished CSV, ready to send. */
    public record CsvFile(String filename, byte[] content) {}

    private record Loaded(Period period, List<HandoffSummaryResponse> all, List<HandoffSummaryResponse> matching, ZoneId zone) {}

    private final HandoffRepository handoffs;
    private final HandoffMapper mapper;
    private final ReturnQueryService returns;
    private final UserService users;

    public HandoffReportService(HandoffRepository handoffs, HandoffMapper mapper, ReturnQueryService returns, UserService users) {
        this.handoffs = handoffs;
        this.mapper = mapper;
        this.returns = returns;
        this.users = users;
    }

    /** The totals for the period and one page of the handoffs that match the filters, sorted as asked. */
    public HandoffReportResponse report(Long ownerId, Query query, Pageable pageable) {
        Loaded loaded = load(ownerId, query, pageable.getSort());
        List<HandoffSummaryResponse> matching = loaded.matching();
        int offset = (int) Math.min(pageable.getOffset(), matching.size());
        List<HandoffSummaryResponse> slice = matching.subList(offset, Math.min(offset + pageable.getPageSize(), matching.size()));
        return new HandoffReportResponse(loaded.period(), summarise(loaded.all()),
                PageResponse.of(new PageImpl<>(slice, pageable, matching.size()), Function.identity()));
    }

    /**
     * The rows the report table is made of — the whole period narrowed by the filters, not one page — for a reader that wants to ask
     * questions of them (the Report Assistant): exactly what {@link #report} and {@link #csv} work from, so anything worked out from them
     * agrees with what the pages show. Same gate, same period rules, the customer's own handoffs only.
     */
    public List<HandoffSummaryResponse> rows(Long ownerId, Query query) {
        return load(ownerId, query, Sort.unsorted()).matching();
    }

    /** Every handoff that matches the filters (not one page), with the period, the filters and the totals above them. */
    public CsvFile csv(Long ownerId, Query query, Sort sort) {
        Loaded loaded = load(ownerId, query, sort);
        Period period = loaded.period();
        Summary s = summarise(loaded.all());

        StringBuilder out = new StringBuilder(Csv.BOM);
        Csv.row(out, "HandOffly handoff report");
        Csv.row(out, "Handoffs created from", period.from().toString());
        Csv.row(out, "Handoffs created to (included)", period.to().toString());
        Csv.row(out, "Time zone", period.timezone());
        Csv.row(out, "Showing", filterText(query));
        Csv.row(out, "Generated (UTC)", Instant.now().toString());
        Csv.row(out);
        Csv.row(out, "Summary of the whole period, whatever the status");
        Csv.row(out, "Handoffs created", String.valueOf(s.created()));
        Csv.row(out, "Handoffs closed", String.valueOf(s.closed()));
        Csv.row(out, "Open handoffs (now)", String.valueOf(s.open()));
        Csv.row(out, "Overdue handoffs (now)", String.valueOf(s.overdue()));
        Csv.row(out, "Items given", Csv.number(s.itemsGiven()));
        Csv.row(out, "Items returned", Csv.number(s.itemsReturned()));
        Csv.row(out, "Items missing", Csv.number(s.itemsMissing()));
        Csv.row(out, "Items still out", Csv.number(s.itemsStillOut()));
        Csv.row(out, "Items on rejected handoffs", Csv.number(s.itemsRejected()));
        Csv.row(out, "Items on cancelled handoffs", Csv.number(s.itemsCancelled()));
        Csv.row(out);
        Csv.row(out, "Reference", "Title", "Recipient", "Created", "Expected return", "Status", "Overdue",
                "Given", "Returned", "Missing");
        for (HandoffSummaryResponse h : loaded.matching()) {
            boolean sent = h.outgoingAt() != null;   // a draft has given nothing: its quantities stay blank, as on screen
            Csv.row(out, Csv.safe(h.publicCode()), Csv.safe(h.title()), Csv.safe(h.recipientName()),
                    day(h.createdAt(), loaded.zone()), day(h.dueAt(), loaded.zone()), label(h.status()),
                    h.overdue() ? "Yes" : "No",
                    sent ? Csv.number(h.totalOutgoing()) : "", sent ? Csv.number(h.totalReturned()) : "",
                    sent ? Csv.number(h.totalMissing()) : "");
        }
        String name = "handoff-report-" + period.from() + "-to-" + period.to() + ".csv";
        return new CsvFile(name, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the one pass over the period

    private Loaded load(Long ownerId, Query query, Sort sort) {
        users.requireActiveSubscription(ownerId);   // before anything is read: the same gate as the rest of the paid product

        if (query.from().isAfter(query.to())) {
            throw new BadRequestException("The start date must not be after the end date.");
        }
        if (query.from().isBefore(EARLIEST) || query.to().isAfter(LATEST)) {
            throw new BadRequestException("That date range is not valid.");
        }
        ZoneId zone = zoneOf(query.timezone());
        Instant from = query.from().atStartOfDay(zone).toInstant();
        Instant to = query.to().plusDays(1).atStartOfDay(zone).toInstant();   // the end date is included in full
        if (handoffs.countByOwnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(ownerId, from, to) > MAX_HANDOFFS) {
            throw new BadRequestException("This period has more than " + MAX_HANDOFFS + " handoffs. Choose a shorter period.");
        }

        List<Handoff> inPeriod = handoffs.findWithItemsByOwnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(ownerId, from, to);
        Map<Long, BigDecimal> returnedGood = returns.confirmedReturnedByItem(ownerId, from, to);
        Map<Long, BigDecimal> declaredMissing = returns.declaredMissingByItem(ownerId, from, to);
        List<HandoffSummaryResponse> all = inPeriod.stream().map(h -> mapper.toSummary(h, returnedGood, declaredMissing)).toList();

        List<HandoffSummaryResponse> matching = all.stream().filter(h -> matches(h, query)).sorted(comparator(sort)).toList();
        return new Loaded(new Period(query.from(), query.to(), zone.getId()), all, matching, zone);
    }

    private static boolean matches(HandoffSummaryResponse h, Query query) {
        boolean statusOk = query.statuses() == null || query.statuses().isEmpty() || query.statuses().contains(h.status());
        return statusOk && (!query.overdueOnly() || h.overdue());
    }

    /** The totals of a list of handoffs — the one place they are worked out, for the report, the CSV and the Report Assistant alike. */
    static Summary summarise(List<HandoffSummaryResponse> all) {
        long closed = 0;
        long open = 0;
        long overdue = 0;
        BigDecimal given = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal missing = BigDecimal.ZERO;
        BigDecimal stillOut = BigDecimal.ZERO;
        BigDecimal rejected = BigDecimal.ZERO;
        BigDecimal cancelled = BigDecimal.ZERO;
        for (HandoffSummaryResponse h : all) {
            if (h.status() == HandoffStatus.CLOSED) closed++;
            if (HandoffActivityService.OPEN_STATUSES.contains(h.status())) open++;
            if (h.overdue()) overdue++;
            given = given.add(itemsGiven(h));
            returned = returned.add(itemsReturned(h));
            missing = missing.add(itemsMissing(h));
            stillOut = stillOut.add(itemsStillOut(h));
            if (h.outgoingAt() != null) {   // what stayed on a rejected or cancelled handoff is told apart from what is still out
                switch (h.status()) {
                    case REJECTED -> rejected = rejected.add(h.totalRemaining());
                    case CANCELLED -> cancelled = cancelled.add(h.totalRemaining());
                    default -> { }
                }
            }
        }
        return new Summary(all.size(), closed, open, overdue, given, returned, missing, stillOut, rejected, cancelled);
    }

    /**
     * One handoff's share of each total above — the single definition of a given, returned, missing and still-out quantity, used by
     * {@link #summarise} and by the Report Assistant's questions alike. A draft was never given to anyone, so it has none. What is neither
     * back nor missing is the handoff's remaining (outgoing = returned + missing + remaining); it is told apart by where it stayed, so
     * the figures add up to the items given: on a rejected or cancelled handoff it is not "still out".
     */
    static BigDecimal itemsGiven(HandoffSummaryResponse h) {
        return h.outgoingAt() == null ? BigDecimal.ZERO : h.totalOutgoing();
    }

    static BigDecimal itemsReturned(HandoffSummaryResponse h) {
        return h.outgoingAt() == null ? BigDecimal.ZERO : h.totalReturned();
    }

    static BigDecimal itemsMissing(HandoffSummaryResponse h) {
        return h.outgoingAt() == null ? BigDecimal.ZERO : h.totalMissing();
    }

    static BigDecimal itemsStillOut(HandoffSummaryResponse h) {
        boolean stayed = h.status() == HandoffStatus.REJECTED || h.status() == HandoffStatus.CANCELLED;
        return h.outgoingAt() == null || stayed ? BigDecimal.ZERO : h.totalRemaining();
    }

    // ------------------------------------------------------------------ sorting

    /** The columns the report can be sorted by; anything else is refused. Ties always fall back on the reference order. */
    private static Comparator<HandoffSummaryResponse> comparator(Sort sort) {
        Comparator<HandoffSummaryResponse> result = null;
        for (Sort.Order order : sort) {
            boolean asc = order.isAscending();
            Comparator<HandoffSummaryResponse> next = switch (order.getProperty()) {
                case "reference" -> by(HandoffSummaryResponse::id, asc);   // references are issued in sequence, so id order is reference order
                case "title" -> by(h -> h.title().toLowerCase(Locale.ROOT), asc);
                case "recipient" -> by(h -> h.recipientName().toLowerCase(Locale.ROOT), asc);
                case "created" -> by(HandoffSummaryResponse::createdAt, asc);
                case "expectedReturn" -> by(HandoffSummaryResponse::dueAt, asc);
                case "status" -> by(HandoffSummaryResponse::status, asc);   // declared in lifecycle order
                case "given" -> by(h -> h.outgoingAt() == null ? null : h.totalOutgoing(), asc);
                case "returned" -> by(h -> h.outgoingAt() == null ? null : h.totalReturned(), asc);
                case "missing" -> by(h -> h.outgoingAt() == null ? null : h.totalMissing(), asc);
                default -> throw new BadRequestException("A report cannot be sorted by \"" + order.getProperty() + "\".");
            };
            result = result == null ? next : result.thenComparing(next);
        }
        Comparator<HandoffSummaryResponse> byReference = Comparator.comparing(HandoffSummaryResponse::id);
        return result == null ? byReference : result.thenComparing(byReference);
    }

    /** Orders by one column in the asked direction, with handoffs that have no value for it (no due date, a draft) always last. */
    private static <T extends Comparable<? super T>> Comparator<HandoffSummaryResponse> by(
            Function<HandoffSummaryResponse, T> key, boolean ascending) {
        Comparator<T> direction = ascending ? Comparator.<T>naturalOrder() : Comparator.<T>reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(direction));
    }

    // ------------------------------------------------------------------ wording and parsing

    /** Which part of the period the table and the CSV show, in words. */
    private static String filterText(Query query) {
        String statuses = query.statuses() == null || query.statuses().isEmpty() ? ""
                : query.statuses().stream().map(HandoffReportService::label).collect(Collectors.joining(" or "));
        if (query.overdueOnly()) return statuses.isEmpty() ? "Overdue handoffs only" : statuses + ", overdue only";
        return statuses.isEmpty() ? "All statuses" : statuses;
    }

    /** "ACTIVE_WITH_RECIPIENT" as "Active with recipient" — the same wording the screens use for a status. */
    static String label(HandoffStatus status) {
        String name = status.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String day(Instant at, ZoneId zone) {
        return at == null ? "" : at.atZone(zone).toLocalDate().toString();
    }

    static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) return UTC;
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new BadRequestException("That timezone is not recognised. Use a name such as Asia/Kolkata or Europe/London.");
        }
    }
}
