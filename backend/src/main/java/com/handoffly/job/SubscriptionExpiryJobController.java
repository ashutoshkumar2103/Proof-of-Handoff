package com.handoffly.job;

import com.handoffly.job.dto.ExpiryJobResponse;
import com.handoffly.job.dto.ExpiryJobRunResponse;
import com.handoffly.job.dto.SetEnabledRequest;
import com.handoffly.job.dto.UpdateExpiryJobRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The subscription-expiry reminder job, for the support portal. It sits behind the support security chain, and every
 * endpoint needs MANAGE_CUSTOMERS — the permission that already covers changing a customer's plan, which administrators
 * and managers hold and ticket agents do not. The job only emails customers; none of this can change a plan.
 */
@RestController
@RequestMapping("/api/v1/support/jobs/subscription-expiry")
@PreAuthorize("hasAuthority('MANAGE_CUSTOMERS')")
public class SubscriptionExpiryJobController {

    private final SubscriptionExpiryJobService service;

    public SubscriptionExpiryJobController(SubscriptionExpiryJobService service) {
        this.service = service;
    }

    @GetMapping
    public ExpiryJobResponse get() {
        return service.get();
    }

    @PutMapping("/schedule")
    public ExpiryJobResponse update(@Valid @RequestBody UpdateExpiryJobRequest request) {
        return service.update(request.cronExpression(), request.timezone(), request.windowDays());
    }

    @PutMapping("/enabled")
    public ExpiryJobResponse setEnabled(@Valid @RequestBody SetEnabledRequest request) {
        return service.setEnabled(request.enabled());
    }

    /** Runs the job once, now. Whether it is on, and its schedule, are not touched. */
    @PostMapping("/run")
    public ExpiryJobRunResponse run() {
        return service.runNow();
    }
}
