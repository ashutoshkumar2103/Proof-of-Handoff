package com.handoffly.job;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.job.dto.JobResponse;
import com.handoffly.job.dto.JobRunResponse;
import com.handoffly.job.dto.SetEnabledRequest;
import com.handoffly.job.dto.UpdateScheduleRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in customer's own jobs. Every call acts on the caller's jobs only: a job is addressed by its kind, and
 * the owner is always the authenticated customer — there is no id in a URL that could name someone else's.
 */
@RestController
@RequestMapping("/api/v1/account/jobs")
public class CustomerJobController {

    private final CustomerJobService service;

    public CustomerJobController(CustomerJobService service) {
        this.service = service;
    }

    @GetMapping
    public List<JobResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return service.list(principal.id());
    }

    /** Runs all four jobs once, now. No schedule changes. */
    @PostMapping("/run-all")
    public List<JobRunResponse> runAll(@AuthenticationPrincipal UserPrincipal principal) {
        return service.runAllNow(principal.id());
    }

    @PutMapping("/{type}/schedule")
    public JobResponse updateSchedule(@AuthenticationPrincipal UserPrincipal principal, @PathVariable JobType type,
                                      @Valid @RequestBody UpdateScheduleRequest request) {
        return service.updateSchedule(principal.id(), type, request.cronExpression(), request.timezone());
    }

    @PutMapping("/{type}/enabled")
    public JobResponse setEnabled(@AuthenticationPrincipal UserPrincipal principal, @PathVariable JobType type,
                                  @Valid @RequestBody SetEnabledRequest request) {
        return service.setEnabled(principal.id(), type, request.enabled());
    }

    /** Runs this job once, now. The schedule and whether it is on are not touched. */
    @PostMapping("/{type}/run")
    public JobRunResponse run(@AuthenticationPrincipal UserPrincipal principal, @PathVariable JobType type) {
        return service.runNow(principal.id(), type);
    }
}
