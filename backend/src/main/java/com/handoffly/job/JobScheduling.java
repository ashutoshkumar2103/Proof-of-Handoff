package com.handoffly.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The one shared ticker behind every job: every minute or so it asks the customer-job service, and the support team's
 * expiry job, to run whatever has come due. The ticker knows nothing about customers — which jobs exist, and for whom, is all in the data —
 * and it is switched off wholesale with {@code handoffly.jobs.scheduler-enabled=false} (as the tests do).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "handoffly.jobs.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduling {

    private final CustomerJobService jobs;
    private final SubscriptionExpiryJobService expiryJob;

    public JobScheduling(CustomerJobService jobs, SubscriptionExpiryJobService expiryJob) {
        this.jobs = jobs;
        this.expiryJob = expiryJob;
    }

    @Scheduled(fixedDelayString = "${handoffly.jobs.poll-seconds:60}", initialDelayString = "${handoffly.jobs.poll-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    void runDueJobs() {
        Instant now = Instant.now();
        jobs.runDue(now);
        expiryJob.runDue(now);
    }
}
