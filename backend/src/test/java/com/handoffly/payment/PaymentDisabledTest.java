package com.handoffly.payment;

import com.handoffly.testsupport.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo provider hands out plans for free, so with it switched off (the default) nobody can get one that way. */
@TestPropertySource(properties = "handoffly.payment.demo-enabled=false")
class PaymentDisabledTest extends ApiTestBase {

    @Test
    void withTheDemoProviderOffPayingIsRefusedAndNothingIsRecorded() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class);
        for (String plan : new String[]{"MONTHLY", "QUARTERLY", "HALF_YEARLY", "YEARLY"}) {
            mvc.perform(post("/api/v1/public/payments/demo").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"plan\":\"" + plan + "\",\"cardNumber\":\"4242 4242 4242 4242\",\"expiry\":\"12/99\",\"cvc\":\"123\"}"))
                    .andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isEqualTo(before);
    }
}
