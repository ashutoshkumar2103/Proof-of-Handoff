package com.handoffly.job;

import com.handoffly.testsupport.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Running jobs by hand is limited per customer, so the button cannot be used to flood anyone's mailbox. */
@TestPropertySource(properties = {
        "handoffly.rate-limit.enabled=true",
        "handoffly.rate-limit.job-runs-per-user=5",
        "handoffly.rate-limit.register-per-ip=1000",
        "handoffly.rate-limit.login-per-ip=1000"})
class CustomerJobRateLimitTest extends ApiTestBase {

    private static final String JOBS = "/api/v1/account/jobs";

    @Test
    void runningJobsByHandIsLimitedPerCustomerAndNotForEveryone() throws Exception {
        Account a = register();
        Account b = register();

        mvc.perform(as(a, post(JOBS + "/run-all"))).andExpect(status().isOk());   // one run per job: five, the whole allowance
        mvc.perform(as(a, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(as(a, post(JOBS + "/run-all"))).andExpect(status().isTooManyRequests());

        mvc.perform(as(b, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isOk());   // another customer is unaffected
    }
}
