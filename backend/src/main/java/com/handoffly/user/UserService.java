package com.handoffly.user;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

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

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found."));
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
        String like = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
        return userRepository.search(like, pageable);
    }

    /**
     * Sets the prefix that NEW handoffs of this customer get. Existing handoffs keep the reference
     * they were issued, and the customer's running number is untouched. Row-locked so it cannot
     * interleave with that customer creating a handoff.
     * @throws BadRequestException if the prefix is not 2-5 upper-case letters
     */
    @Transactional
    public AccountChange changeHandoffPrefix(String accountCode, String prefix) {
        User customer = lockedCustomer(accountCode);
        String previous = customer.getHandoffPrefix();
        try {
            customer.changeHandoffPrefix(prefix);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        return new AccountChange(customer, previous, prefix);
    }

    /**
     * Moves the customer to another plan; what they may use follows from the plan alone. The caller names
     * the plan they believe the customer is on, so a change made on a stale view is refused rather than
     * silently applied.
     * @throws ConflictException if the customer's plan is not {@code expectedCurrent}, or is already {@code newPlan}
     */
    @Transactional
    public AccountChange changeSubscriptionPlan(String accountCode, SubscriptionPlan expectedCurrent,
                                                SubscriptionPlan newPlan) {
        User customer = lockedCustomer(accountCode);
        SubscriptionPlan current = customer.getSubscriptionPlan();
        if (current != expectedCurrent) {
            throw new ConflictException("The customer is on the " + current + " plan, not " + expectedCurrent
                    + ". Reload the page and try again.");
        }
        if (current == newPlan) {
            throw new ConflictException("The customer is already on the " + newPlan + " plan.");
        }
        customer.setSubscriptionPlan(newPlan);
        return new AccountChange(customer, current.name(), newPlan.name());
    }

    private User lockedCustomer(String accountCode) {
        return userRepository.findByAccountCodeForUpdate(accountCode)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
    }
}
