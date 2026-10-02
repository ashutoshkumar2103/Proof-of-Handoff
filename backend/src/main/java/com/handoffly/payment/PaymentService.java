package com.handoffly.payment;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.ApiException;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.util.SecureTokens;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paying for a plan and applying it. A confirmed payment yields a one-time token; a signed-in customer who
 * presents it gets the plan it was paid for. The plan is only ever set here, by the backend, from a payment
 * it recorded itself — the client never says which plan it is entitled to. Today's only provider is the
 * DEMO one (no money, off unless explicitly enabled).
 */
@Service
public class PaymentService {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern EXPIRY_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])\\s*/\\s*(\\d{2})$");
    private static final String CVC_PATTERN = "\\d{3,4}";

    /** A new payment and the raw token that applies it (shown once; only its hash is stored). */
    public record PaidPlan(Payment payment, String token) {}

    private final PaymentRepository payments;
    private final UserService userService;
    private final HandOfflyProperties.Payment settings;

    public PaymentService(PaymentRepository payments, UserService userService, HandOfflyProperties properties) {
        this.payments = payments;
        this.userService = userService;
        this.settings = properties.getPayment();
    }

    /**
     * The demo provider "takes" a payment for the plan with a demo test card. No account is needed yet: the
     * payment is made first, and the token applies it when the customer signs up or signs in. The card details
     * are checked and dropped; only a card that is one of the {@link DemoCard}s can pay, and the declined test
     * card is declined.
     * @throws NotFoundException if the demo provider is switched off (as if there were no such endpoint)
     * @throws BadRequestException if the card is not a demo test card, or its expiry or security code is not valid
     * @throws ApiException (402) if it is the test card that is always declined
     */
    @Transactional
    public PaidPlan payDemo(SubscriptionPlan plan, String cardNumber, String expiry, String cvc) {
        if (!settings.isDemoEnabled()) {
            throw new NotFoundException("Payments are not available right now.");
        }
        requireValidExpiry(expiry);
        if (!cvc.matches(CVC_PATTERN)) {
            throw new BadRequestException("Enter the 3 or 4 digit security code.");
        }
        DemoCard card = DemoCard.find(cardNumber).orElseThrow(() -> new BadRequestException(
                "This is a demo payment page: only the demo test cards listed on it work. Real cards are not accepted."));
        if (card == DemoCard.DECLINED) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "card_declined",
                    "Your card was declined (demo test card). Try another card.");
        }
        Instant now = Instant.now();
        String token = SecureTokens.randomToken(TOKEN_BYTES);
        Payment payment = payments.save(new Payment(PaymentProvider.DEMO, plan, SecureTokens.sha256Hex(token),
                now, now.plus(Duration.ofHours(settings.getRedeemTtlHours()))));
        return new PaidPlan(payment, token);
    }

    /** MM/YY, and not in the past (the card is good through the end of its month). */
    private static void requireValidExpiry(String expiry) {
        Matcher m = EXPIRY_PATTERN.matcher(expiry.trim());
        if (!m.matches()) {
            throw new BadRequestException("Enter the expiry date as MM/YY.");
        }
        YearMonth expires = YearMonth.of(2000 + Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        if (expires.isBefore(YearMonth.now(ZoneOffset.UTC))) {
            throw new BadRequestException("That card has expired.");
        }
    }

    /**
     * Applies a paid-for plan to this customer, once. An unknown, expired or already-used token is refused
     * with the same message, so a token cannot be probed.
     * @return the customer, now on the plan they paid for
     */
    @Transactional
    public User redeem(Long userId, String rawToken) {
        Payment payment = payments.findByTokenHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .filter(p -> p.isRedeemable(Instant.now()))
                .orElseThrow(() -> new BadRequestException(
                        "This payment cannot be applied: it is unknown, expired or already used."));
        SubscriptionPlan before = userService.applyPaidPlan(userId, payment.getPlan());
        User customer = userService.getById(userId);
        payment.redeem(customer, before, Instant.now());
        return customer;
    }
}
