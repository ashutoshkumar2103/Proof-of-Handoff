package com.handoffly.support;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.support.dto.PlanPriceResponse;
import com.handoffly.support.dto.PublicContactResponse;
import com.handoffly.user.SubscriptionPlan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Public information, open to everyone (it is all on the public website): the general contact address —
 * including for customers whose plan has no Contact Support area — and the plans' list prices, which the
 * pricing page and the support portal both read so prices live in one place.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicInfoController {

    private final String email;

    public PublicInfoController(HandOfflyProperties properties) {
        this.email = properties.getSupport().getMailbox();
    }

    @GetMapping("/contact")
    public PublicContactResponse contact() {
        return new PublicContactResponse(email);
    }

    /** Every plan with its price, cheapest billing period first. */
    @GetMapping("/plans")
    public List<PlanPriceResponse> plans() {
        return Arrays.stream(SubscriptionPlan.values()).map(PlanPriceResponse::from).toList();
    }
}
