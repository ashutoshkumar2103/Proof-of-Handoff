package com.handoffly.payment;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.auth.dto.UserResponse;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.payment.dto.DemoPaymentRequest;
import com.handoffly.payment.dto.PaymentReceiptResponse;
import com.handoffly.payment.dto.RedeemPaymentRequest;
import com.handoffly.payment.dto.UpgradeOptionResponse;
import com.handoffly.payment.dto.UpgradePaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Paying for a plan and applying it. Paying needs no account (the pricing page leads here first); applying
 * needs the signed-in customer, and only the backend decides what plan they end up on. A signed-in customer on an
 * active plan can also pay just the difference to move up to a dearer one.
 */
@RestController
public class PaymentController {

    private final PaymentService service;
    private final String supportPhone;

    public PaymentController(PaymentService service, HandOfflyProperties properties) {
        this.service = service;
        this.supportPhone = properties.getSupport().getPhone();
    }

    /** Public: the demo provider "takes" a payment for a plan from a demo test card (404 unless the demo provider is enabled). */
    @PostMapping("/api/v1/public/payments/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentReceiptResponse payDemo(@Valid @RequestBody DemoPaymentRequest request) {
        return PaymentReceiptResponse.from(
                service.payDemo(request.plan(), request.cardNumber(), request.expiry(), request.cvc()));
    }

    /** The signed-in customer applies a paid-for plan to their own account; returns the account as it is now. */
    @PostMapping("/api/v1/payments/redeem")
    public UserResponse redeem(@AuthenticationPrincipal UserPrincipal principal,
                               @Valid @RequestBody RedeemPaymentRequest request) {
        return UserResponse.from(service.redeem(principal.id(), request.token()), supportPhone);
    }

    /** The plans the signed-in customer can move up to from the plan they are on, each priced as what is left to pay. */
    @GetMapping("/api/v1/payments/upgrades")
    public List<UpgradeOptionResponse> upgradeOptions(@AuthenticationPrincipal UserPrincipal principal) {
        return service.upgradeOptions(principal.id()).stream().map(UpgradeOptionResponse::from).toList();
    }

    /**
     * The signed-in customer pays the difference to move up to a dearer plan, and has it at once; returns the account as it is
     * now (404 unless the demo provider is enabled, 409 if there is nothing to upgrade or the price is not what they saw).
     */
    @PostMapping("/api/v1/payments/upgrade")
    public UserResponse upgrade(@AuthenticationPrincipal UserPrincipal principal,
                                @Valid @RequestBody UpgradePaymentRequest request) {
        DemoPaymentRequest card = request.payment();
        return UserResponse.from(service.payUpgradeDemo(principal.id(), card.plan(), request.expectedAmount(),
                card.cardNumber(), card.expiry(), card.cvc()), supportPhone);
    }
}
