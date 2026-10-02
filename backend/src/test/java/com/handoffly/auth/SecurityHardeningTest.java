package com.handoffly.auth;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What every response carries, who can reach whose data, and that a disguised upload is turned away. */
class SecurityHardeningTest extends ApiTestBase {

    private record Call(HttpMethod method, String path, String json) {}

    // ------------------------------------------------------------------ headers

    @Test
    void everyApiResponseCarriesTheSecurityHeaders() throws Exception {
        Account customer = register();
        StaffAccount staff = registerStaff(SupportRole.ADMIN);

        for (MvcResult r : List.of(
                mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(status().isOk()).andReturn(),
                mvc.perform(as(staff, get("/api/v1/support/dashboard"))).andExpect(status().isOk()).andReturn(),
                mvc.perform(get("/api/v1/public/plans")).andExpect(status().isOk()).andReturn(),
                mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized()).andReturn())) {
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(r.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        }
    }

    @Test
    void transportSecurityIsDemandedOnlyOverHttps() throws Exception {
        mvc.perform(get("/api/v1/public/plans").secure(true))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("includeSubDomains")));
        mvc.perform(get("/api/v1/public/plans")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    // ------------------------------------------------------------------ isolation of customer-owned data

    @Test
    void anotherCustomerCanNeitherReadNorChangeAHandoffThroughAnyEndpoint() throws Exception {
        Account owner = register();
        Account intruder = register(com.handoffly.user.SubscriptionPlan.YEARLY);   // a plan with HandoffCheck: the refusals below are about ownership
        String created = createHandoff(owner);
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        long itemId = ((Number) JsonPath.read(created, "$.items[0].id")).longValue();
        String h = "/api/v1/handoffs/" + id;
        String header = "{\"title\":\"Hacked\",\"senderName\":\"x\",\"recipientName\":\"y\",\"recipientEmail\":\"y@example.test\"}";
        String ret = "{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"GOOD\"}]}";

        List<Call> calls = List.of(
                new Call(HttpMethod.GET, h, null), new Call(HttpMethod.GET, h + "/events", null),
                new Call(HttpMethod.GET, h + "/pdf", null), new Call(HttpMethod.POST, h + "/email-pdf", "{}"),
                new Call(HttpMethod.PATCH, h, header),
                new Call(HttpMethod.PUT, h + "/items", "{\"items\":[{\"name\":\"Stolen\",\"quantity\":1}]}"),
                new Call(HttpMethod.POST, h + "/submit", null), new Call(HttpMethod.POST, h + "/resend-link", null),
                new Call(HttpMethod.POST, h + "/cancel", "{}"), new Call(HttpMethod.POST, h + "/dispute", "{}"),
                new Call(HttpMethod.POST, h + "/close", "{}"),
                new Call(HttpMethod.POST, h + "/request-missing-confirmation", null),
                new Call(HttpMethod.POST, h + "/returns", ret), new Call(HttpMethod.POST, h + "/returns/1/confirm", null),
                new Call(HttpMethod.GET, h + "/attachments", null), new Call(HttpMethod.GET, h + "/attachments/1/content", null),
                new Call(HttpMethod.DELETE, h + "/attachments/1", null),
                new Call(HttpMethod.DELETE, h, null));

        for (Call c : calls) {
            MockHttpServletRequestBuilder anonymous = call(c);
            mvc.perform(anonymous).andExpect(status().isUnauthorized());
            MvcResult result = mvc.perform(as(intruder, call(c))).andReturn();
            int code = result.getResponse().getStatus();
            assertThat(code).as(c.method() + " " + c.path()).isBetween(400, 404);   // refused, never served
            assertThat(code).as(c.method() + " " + c.path()).isNotEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).as(c.path()).doesNotContain("Laptop handout", "hire@example.test");
        }
        // The upload and the HandoffCheck endpoints that take a handoff id are closed to outsiders too.
        mvc.perform(as(intruder, multipart(h + "/attachments").file(new MockMultipartFile("file", "x.txt", "text/plain", "x".getBytes()))
                .param("kind", "EVIDENCE"))).andExpect(status().isForbidden());
        mvc.perform(as(intruder, multipart("/api/v1/handoff-check/return-import").param("handoffId", String.valueOf(id))
                .file(new MockMultipartFile("file", "r.csv", "text/csv", "Item,Qty\nLaptop,1".getBytes()))))
                .andExpect(status().isForbidden());
        mvc.perform(as(intruder, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"referenceLines\":[{\"name\":\"Laptop\",\"quantity\":1}],\"handoffId\":" + id + "}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));   // not plan_required

        // Nothing of the owner's changed, and the intruder's own list never shows it.
        mvc.perform(as(owner, get(h))).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Laptop handout"))
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(as(intruder, get("/api/v1/handoffs"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anotherCustomerAndAnonymousCallersCannotReachSupportData() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String ticket = createTicket(customer, "Private question");
        Account other = register(SubscriptionPlan.HALF_YEARLY);

        for (String path : List.of("/api/v1/support/dashboard", "/api/v1/support/customers", "/api/v1/support/tickets",
                "/api/v1/support/tickets/" + ticket, "/api/v1/support/staff", "/api/v1/support/audit")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(as(other, get(path))).andExpect(status().isUnauthorized());   // a customer token is not a staff token
        }
        mvc.perform(as(other, get("/api/v1/tickets/" + ticket))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ uploads

    @Test
    void aDisguisedFileIsRefusedEverywhereFilesAreAccepted() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        long handoffId = ((Number) JsonPath.read(createHandoff(customer), "$.id")).longValue();
        String ticket = createTicket(customer, "With a file");
        byte[] script = "<script>alert('hi')</script>".getBytes();
        MockMultipartFile fakePng = new MockMultipartFile("file", "photo.png", "image/png", script);
        MockMultipartFile fakePdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", script);

        mvc.perform(as(customer, multipart("/api/v1/handoffs/" + handoffId + "/attachments").file(fakePng).param("kind", "EVIDENCE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("does not match")));
        mvc.perform(as(customer, multipart("/api/v1/tickets/" + ticket + "/attachments").file(fakePdf)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(customer, multipart("/api/v1/support-messages").file(fakePng).param("subject", "s").param("message", "m")))
                .andExpect(status().isBadRequest());
        // ...and a real one is still welcome.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(as(customer, multipart("/api/v1/handoffs/" + handoffId + "/attachments")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", png)).param("kind", "EVIDENCE")))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder call(Call c) {
        MockHttpServletRequestBuilder b = request(c.method(), c.path());
        if (c.json() != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(c.json());
        }
        return b;
    }
}
