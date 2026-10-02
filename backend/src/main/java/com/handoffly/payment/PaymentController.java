package com.handoffly.payment;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.auth.dto.UserResponse;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.payment.dto.DemoPaymentRequest;
import com.handoffly.payment.dto.PaymentReceiptResponse;
import com.handoffly.payment.dto.RedeemPaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying for a plan and applying it. Paying needs no account (the pricing page leads here first); applying
 * needs the signed-in customer, and only the backend decides what plan they end up on.
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
}
