package com.handoffly.handoff;

import com.handoffly.common.util.EmailAddress;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A recipient's email must be a real-looking address: something like {@code name@ee} is a typo that could never be delivered to. */
class HandoffRecipientEmailTest extends ApiTestBase {

    private static final Pattern RULE = Pattern.compile(EmailAddress.REGEX);

    private static final List<String> REJECTED = List.of(
            "ashut@ee",                 // no dot in the domain (what @Email let through)
            "a@b", "name@localhost",
            "name@example.",            // ends at the dot
            "name@.example.com",
            "name@example..com",
            "name@example.c",           // one-letter top-level domain
            "name@example.123",         // numeric top-level domain
            "name@-example.com", "name@example-.com",
            "name example@example.com", "name@exa mple.com",
            "name@@example.com", "name@example@example.com",
            "@example.com", "name@",
            ".name@example.com", "name.@example.com", "na..me@example.com",
            "näme@example.com",       // non-ASCII local part
            "a".repeat(65) + "@example.com",   // local part over 64
            "name@" + "a".repeat(64) + ".com", // a domain label over 63
            "name@example.com,other@example.com", "<name@example.com>", "Name <name@example.com>");

    private static final List<String> ACCEPTED = List.of(
            "name@example.com", "first.last@example.com", "first.last+tag@sub.example.co.uk", "o'brien@example.org",
            "user_name-1@ex-ample.io", "a@b.co", "hire@example.test", "x@xn--80ak6aa92e.xn--p1ai", "A.B@EXAMPLE.COM");

    private String body(String email) {
        return "{\"title\":\"T\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"" + email + "\","
                + "\"items\":[{\"name\":\"A\",\"quantity\":1}]}";
    }

    @Test
    void theRuleAcceptsOrdinaryAddressesAndRejectsTheTypos() {
        for (String ok : ACCEPTED) assertThat(RULE.matcher(ok).matches()).as("accepted: " + ok).isTrue();
        for (String bad : REJECTED) assertThat(RULE.matcher(bad).matches()).as("rejected: " + bad).isFalse();
    }

    @Test
    void aHostileVeryLongAddressIsAnsweredAtOnce() {
        List<String> hostile = List.of("name@" + "a".repeat(5000) + ".com", "name@" + "a-".repeat(3000) + "x", "a".repeat(5000) + "@example.com", "name@" + "a.".repeat(3000) + "!");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            for (String email : hostile) assertThat(RULE.matcher(email).matches()).isFalse();
        });
    }

    @Test
    void aHandoffCannotBeCreatedForAnAddressThatCouldNeverBeReached() throws Exception {
        Account owner = register();
        for (String bad : List.of("ashut@ee", "a@b", "name@example.", "name@@example.com", "name example@example.com")) {
            mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body(bad))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("recipientEmail"))
                    .andExpect(jsonPath("$.errors[0].message").value(EmailAddress.MESSAGE));
        }
        mvc.perform(as(owner, get("/api/v1/handoffs"))).andExpect(jsonPath("$.totalElements").value(0));   // nothing was saved
    }

    @Test
    void anOrdinaryAddressStillWorksAndIsKeptAsTyped() throws Exception {
        Account owner = register();
        for (String ok : List.of("name@example.com", "first.last+tag@sub.example.co.uk")) {
            mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body(ok))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.recipientEmail").value(ok));
        }
    }

    @Test
    void editingADraftIsHeldToTheSameRule() throws Exception {
        Account owner = register();
        long id = ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
        String header = "{\"title\":\"T\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"%s\"}";

        mvc.perform(as(owner, patch("/api/v1/handoffs/" + id).contentType(MediaType.APPLICATION_JSON).content(header.formatted("ashut@ee"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("recipientEmail"));
        mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(jsonPath("$.recipientEmail").value("hire@example.test"));   // unchanged

        mvc.perform(as(owner, patch("/api/v1/handoffs/" + id).contentType(MediaType.APPLICATION_JSON).content(header.formatted("new.hire@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientEmail").value("new.hire@example.com"));
    }

    @Test
    void aMissingOrBlankAddressIsStillRefusedAsBefore() throws Exception {
        Account owner = register();
        mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body(""))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"senderName\":\"S\",\"recipientName\":\"R\",\"items\":[]}")))
                .andExpect(status().isBadRequest());
    }
}
