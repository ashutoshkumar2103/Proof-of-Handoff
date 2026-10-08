package com.handoffly.user;

import com.handoffly.common.error.ApiException;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.sequence.SequenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read/lookup operations on customers, shared across modules, plus the two account settings that
 * support staff administer (handoff prefix, subscription plan). Account creation and authentication
 * live in the {@code auth} module.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    /** A setting change on a customer account, with the value before and after (for the audit trail). */
    public record AccountChange(User customer, String previous, String current) {}

    /** The outcome of a support plan change: who it was, and the plan (null: none) and end date they had before. */
    public record PlanChange(User customer, SubscriptionPlan previousPlan, Instant previousValidUntil) {}

    /** The machine-readable error code, and the message, a customer gets when they try something that needs an active subscription. */
    public static final String SUBSCRIPTION_EXPIRED_CODE = "subscription_expired";
    public static final String SUBSCRIPTION_ENDED_MESSAGE =
            "Your subscription has ended. Please subscribe to any of our plans to continue without any interruption.";

    /** The same, for an account that has never had a plan — nothing was paid for or activated yet, as against one whose plan ran out. */
    public static final String NO_ACTIVE_SUBSCRIPTION_CODE = "no_active_subscription";
    public static final String NO_ACTIVE_SUBSCRIPTION_MESSAGE =
            "No active plan is associated with this account. Please contact our support team to activate your account.";

    /** The code, and the message, for a feature the customer's plan does not include (names the plans that do; keep in step with {@link SubscriptionPlan}). */
    public static final String PLAN_REQUIRED_CODE = "plan_required";
    public static final String HANDOFFCHECK_PLAN_MESSAGE = "HandoffCheck is available on Half-Yearly and Yearly plans.";
    public static final String AI_ASSISTANT_PLAN_MESSAGE = "The AI Report Assistant is available on Half-Yearly and Yearly plans.";

    /** A complete Account ID (CUS-01, CUS-120 …); IDs are short, so a partial one is a "contains" search instead. */
    private static final Pattern FULL_ACCOUNT_ID = Pattern.compile("(?i)CUS-\\d{2,}");

    private final UserRepository userRepository;
    private final SubscriptionHistoryRepository history;
    private final SequenceService sequences;

    public UserService(UserRepository userRepository, SubscriptionHistoryRepository history, SequenceService sequences) {
        this.userRepository = userRepository;
        this.history = history;
        this.sequences = sequences;
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    /**
     * The single rule behind everything that needs an active subscription: the customer's subscription must be active
     * right now, judged by the same {@link User#subscriptionStatus} that decides everything else about a subscription.
     * Every caller asks it afresh, inside the request and before it saves or sends anything, so nothing a screen last
     * saw — or a client that never asked — can let one through. Refused with {@code 403} through the ordinary error
     * response: with the code {@value #SUBSCRIPTION_EXPIRED_CODE} when the plan ran out, or {@value #NO_ACTIVE_SUBSCRIPTION_CODE}
     * when the account never had one. Used for starting a handoff, sending a draft and HandoffCheck; the things a customer
     * already has (their handoffs, returns, links, PDFs) never ask it.
     */
    public void requireActiveSubscription(User customer) {
        if (customer.subscriptionStatus(Instant.now()) != SubscriptionStatus.ACTIVE) {
            throw customer.hasPlan()
                    ? new ApiException(HttpStatus.FORBIDDEN, SUBSCRIPTION_EXPIRED_CODE, SUBSCRIPTION_ENDED_MESSAGE)
                    : new ApiException(HttpStatus.FORBIDDEN, NO_ACTIVE_SUBSCRIPTION_CODE, NO_ACTIVE_SUBSCRIPTION_MESSAGE);
        }
    }

    /** The same rule for a customer known only by id (it reads them afresh). */
    public void requireActiveSubscription(Long customerId) {
        requireActiveSubscription(getById(customerId));
    }

    /**
     * HandoffCheck (comparing two files) is a feature of some plans only — see {@link SubscriptionPlan#includesHandoffCheck}.
     * The plan is the only source of truth: nothing is stored per customer, so a plan change changes this at once. Like every
     * other plan-dependent feature it asks {@link User#entitledPlan}, never the raw plan, and callers ask
     * {@link #requireActiveSubscription} first, so a lapsed subscription is answered as "ended", not as "not in your plan".
     * Refused with {@code 403} and the code {@value #PLAN_REQUIRED_CODE}.
     */
    public void requireHandoffCheckPlan(User customer) {
        if (!customer.entitledPlan().includesHandoffCheck()) {
            throw new ApiException(HttpStatus.FORBIDDEN, PLAN_REQUIRED_CODE, HANDOFFCHECK_PLAN_MESSAGE);
        }
    }

    /**
     * The AI features are offered on the plans that include HandoffCheck and no others — the same rule ({@link SubscriptionPlan#includesHandoffCheck}),
     * not a second entitlement, so nothing is stored per customer and a plan change changes it at once. Refused like
     * {@link #requireHandoffCheckPlan}, with its own wording, so the customer is told which feature the plan does not include.
     */
    public void requireAiAssistantPlan(User customer) {
        if (!customer.entitledPlan().includesHandoffCheck()) {
            throw new ApiException(HttpStatus.FORBIDDEN, PLAN_REQUIRED_CODE, AI_ASSISTANT_PLAN_MESSAGE);
        }
    }

    /**
     * Loads the user with a row lock held until the caller's transaction ends. Used where a
     * per-account counter must be advanced safely (handoff numbering). Must run inside the caller's
     * read-write transaction.
     */
    public User getByIdForUpdate(Long id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    /** A customer by its public account ID. */
    public User getCustomerByAccountCode(String accountCode) {
        return userRepository.findByAccountCode(accountCode)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
    }

    /** Customers whose account ID, name or email contains the text (case-insensitive); everyone when it is blank. */
    public Page<User> searchCustomers(String query, Pageable pageable) {
        String text = query == null ? "" : query.trim();
        // A complete Account ID names exactly one customer: CUS-12 must not also list CUS-120.
        if (FULL_ACCOUNT_ID.matcher(text).matches()) {
            List<User> found = userRepository.findByAccountCode(text.toUpperCase(Locale.ROOT)).stream().toList();
            return new PageImpl<>(pageable.getPageNumber() == 0 ? found : List.of(), pageable, found.size());
        }
        String like = "%" + text.toLowerCase(Locale.ROOT) + "%";
        return userRepository.search(like, pageable);
    }

    /**
     * The default handoff prefix of a customer who is registering: made from the organization if they gave one, otherwise from their name
     * ({@link HandoffPrefixes}: Siam Traders is ST, Rahul Kumar RK), and one that no customer has now — whatever its case — taking the
     * next choice the rule gives when the first is taken. Chosen under the lock of the account counter, which every registration and every
     * support prefix change holds until it commits, so two of them can never choose the same prefix; the caller must be registering in a
     * transaction and give it to the new customer before it commits. Prefixes of existing customers are never touched.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String reserveDefaultHandoffPrefix(String organization, String displayName) {
        sequences.lock(SequenceService.ACCOUNT);
        Set<String> used = Set.copyOf(userRepository.findCurrentHandoffPrefixes());
        String source = organization != null && !organization.isBlank() ? organization : displayName;
        return HandoffPrefixes.firstFree(source, used);
    }

    /**
     * Sets the prefix that NEW handoffs of this customer get. Existing handoffs keep the reference
     * they were issued, and the customer's running number is untouched. Row-locked so it cannot
     * interleave with that customer creating a handoff. No other customer may have the prefix now (compared without regard to case,
     * under the same lock as {@link #reserveDefaultHandoffPrefix}); saying the customer's own prefix again is not a change.
     * @throws BadRequestException if the prefix is not 2-5 upper-case letters
     * @throws ConflictException if another customer has the prefix
     */
    @Transactional
    public AccountChange changeHandoffPrefix(String accountCode, String prefix) {
        User customer = lockedCustomer(accountCode);
        sequences.lock(SequenceService.ACCOUNT);
        boolean sameAsNow = prefix != null && prefix.equalsIgnoreCase(customer.getHandoffPrefix());   // existing accounts share HO: asking for what one already has is no change
        if (prefix != null && !sameAsNow && userRepository.existsByHandoffPrefixIgnoreCaseAndIdNot(prefix, customer.getId())) {
            throw new ConflictException("The prefix " + prefix.toUpperCase(Locale.ROOT) + " is already used by another customer. Choose a different one.");
        }
        String previous = customer.getHandoffPrefix();
        try {
            customer.changeHandoffPrefix(prefix);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        return new AccountChange(customer, previous, prefix);
    }

    /**
     * Moves the customer to another plan (starting now), puts a customer who has none on one, or — on the plan they
     * already have — changes how long it is paid for. What they may use follows from the plan alone. The caller names
     * the plan they believe the customer is on (null: none), so a change made on a stale view is refused rather than
     * silently applied. The change is appended to the customer's subscription history. A new plan lasts until the last
     * day support names, or — when none is named — for the plan's own duration from now ({@link SubscriptionPlan#validUntil}),
     * so a normal activation needs no date; the same plan needs one, since a change of validity is all that is asked.
     * @throws ConflictException if the customer's plan is not {@code expectedCurrent}, or nothing would change
     */
    @Transactional
    public PlanChange changeSubscriptionPlan(String accountCode, SubscriptionPlan expectedCurrent,
                                             SubscriptionPlan newPlan, Instant validUntil, Long staffId, String reason) {
        User customer = lockedCustomer(accountCode);
        SubscriptionPlan current = customer.getSubscriptionPlan();
        Instant previousValidUntil = customer.getPlanValidUntil();
        if (current != expectedCurrent) {
            throw new ConflictException("The customer is on " + describe(current) + ", not " + describe(expectedCurrent)
                    + ". Reload the page and try again.");
        }
        boolean samePlan = current == newPlan;
        if (samePlan && (validUntil == null || validUntil.equals(previousValidUntil))) {
            throw new ConflictException("The customer is already on the " + newPlan + " plan"
                    + (validUntil == null ? "." : " until that date."));
        }
        Instant now = Instant.now();
        // The same plan with another end date is a change of validity, not a new plan: it keeps its start.
        Instant startsAt = samePlan && customer.getPlanStartedAt() != null ? customer.getPlanStartedAt() : now;
        Instant endsAt = validUntil != null ? validUntil : newPlan.validUntil(now);
        customer.startPlan(newPlan, startsAt, endsAt);
        history.save(new SubscriptionHistory(customer, current, newPlan, startsAt, endsAt,
                SubscriptionChangeSource.STAFF, staffId, reason));
        return new PlanChange(customer, current, previousValidUntil);
    }

    private static String describe(SubscriptionPlan plan) {
        return plan == null ? "no plan" : "the " + plan + " plan";
    }

    /**
     * Puts the customer on a plan they have paid for and returns the plan they were on (null: none — the first plan a
     * customer buys replaces nothing). A payment buys one period of the plan ({@link SubscriptionPlan#validUntil}): from
     * now, or — when they renew the plan they still have, with time left — on from where it runs out. Unlike a support
     * change this needs no expected current plan: what was paid for is what they get. The change is appended to the
     * subscription history. Runs in the caller's transaction.
     */
    public SubscriptionPlan applyPaidPlan(Long userId, SubscriptionPlan plan) {
        User customer = getByIdForUpdate(userId);
        SubscriptionPlan before = customer.getSubscriptionPlan();
        Instant now = Instant.now();
        boolean renewal = before == plan && customer.getPlanValidUntil() != null
                && customer.subscriptionStatus(now) == SubscriptionStatus.ACTIVE;
        Instant startsAt = renewal && customer.getPlanStartedAt() != null ? customer.getPlanStartedAt() : now;
        Instant validUntil = plan.validUntil(renewal ? customer.getPlanValidUntil() : now);
        customer.startPlan(plan, startsAt, validUntil);
        history.save(new SubscriptionHistory(customer, before, plan, startsAt, validUntil,
                SubscriptionChangeSource.PAYMENT, null, null));
        return before;
    }

    /** A customer's plans, newest first (at most the latest 50). */
    public java.util.List<SubscriptionHistory> subscriptionHistory(Long userId) {
        return history.findTop50ByUserIdOrderByIdDesc(userId);
    }

    private User lockedCustomer(String accountCode) {
        return userRepository.findByAccountCodeForUpdate(accountCode)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
    }
}
