package com.handoffly.testsupport;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.support.staff.SupportStaffService;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.User;
import com.handoffly.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for tests that drive the whole API over HTTP: real security, real database (H2) and a
 * captured outbox. Every test registers its own uniquely-named accounts, so tests never see each
 * other's data. Customers and support staff are two separate kinds of account with separate logins.
 * A customer's plan is set straight in the database as test setup; staff are created through the same
 * controlled provisioning service the application uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CapturingEmailSender.Config.class)
public abstract class ApiTestBase {

    protected static final String PASSWORD = "password123";
    protected static final String STAFF_PASSWORD = "staff-test-passw0rd";
    protected static final String SUPPORT_MAILBOX = "support-desk@handoffly.test";
    protected static final String SUPPORT_PHONE = "+1 555 0100";
    protected static final String CUSTOMER_PHONE = "+1 555 0142";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected CapturingEmailSender emailSender;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected SupportStaffService staffService;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Anyone who can present a Bearer token. */
    public interface Bearer {
        String token();
    }

    /** A signed-up CUSTOMER. */
    public record Account(Long id, String accountCode, String email, String token) implements Bearer {}

    /** A signed-in SUPPORT STAFF member (a different identity from a customer). */
    public record StaffAccount(Long id, String staffCode, String email, String token) implements Bearer {}

    /** A new customer on the default (monthly) plan. */
    protected Account register() throws Exception {
        return register(null);
    }

    protected Account register(SubscriptionPlan plan) throws Exception {
        return register("Test Customer", plan);
    }

    protected Account register(String displayName, SubscriptionPlan plan) throws Exception {
        String email = "user-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
                                + "\"displayName\":\"" + displayName + "\",\"phone\":\"" + CUSTOMER_PHONE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        Number id = JsonPath.read(json, "$.user.id");
        Account account = new Account(id.longValue(), JsonPath.read(json, "$.user.accountCode"), email,
                JsonPath.read(json, "$.token"));
        if (plan != null) {
            setPlan(account, plan);
        }
        return account;
    }

    protected Account login(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        Number id = JsonPath.read(json, "$.user.id");
        return new Account(id.longValue(), JsonPath.read(json, "$.user.accountCode"), email, JsonPath.read(json, "$.token"));
    }

    /** A support staff member, created by controlled provisioning and signed in through the support login. */
    protected StaffAccount registerStaff(SupportRole role) throws Exception {
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 12) + "@support.example.test";
        staffService.provision("Support Agent", email, STAFF_PASSWORD, role);
        return loginStaff(email);
    }

    protected StaffAccount loginStaff(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/support/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        return new StaffAccount(staffId(email), JsonPath.read(json, "$.staff.staffCode"), email, JsonPath.read(json, "$.token"));
    }

    /** An operator deactivating a staff member (done in the database; there is no screen for it). */
    protected void deactivate(StaffAccount staff) {
        jdbc.update("UPDATE support_staff SET active = FALSE WHERE id = ?", staff.id());
    }

    protected void setPlan(Account account, SubscriptionPlan plan) {
        User user = users.findById(account.id()).orElseThrow();
        user.setSubscriptionPlan(plan);
        users.saveAndFlush(user);
    }

    /** Signs a request (plain or multipart) as this customer or staff member. */
    protected static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(Bearer who, B request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.token());
    }

    /** Support sets the prefix that a customer's new handoffs get. */
    protected void setPrefix(StaffAccount staff, Account customer, String prefix) throws Exception {
        mvc.perform(as(staff, put("/api/v1/support/customers/" + customer.accountCode() + "/prefix")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prefix\":\"" + prefix + "\"}")))
                .andExpect(status().isOk());
    }

    /** Creates a one-item draft handoff and returns the response JSON (id, publicCode, ...). */
    protected String createHandoff(Account owner) throws Exception {
        String body = """
                {"title":"Laptop handout","senderName":"IT Dept","recipientName":"New Hire",
                 "recipientEmail":"hire@example.test",
                 "items":[{"name":"Laptop","quantity":1,"serialNumber":"SN-1"}]}""";
        return mvc.perform(as(owner, post("/api/v1/handoffs")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** Opens a ticket as this customer (whose plan must include tickets) and returns its ticket ID. */
    protected String createTicket(Account customer, String subject) throws Exception {
        String body = """
                {"subject":"%s","category":"TECHNICAL","description":"The upload button does nothing.",
                 "handoffReference":"av-3"}""".formatted(subject);
        MvcResult res = mvc.perform(as(customer, post("/api/v1/tickets")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.ticket.ticketCode");
    }

    /** Sends a support message as this customer (whose plan must include Contact Support); returns its reference. */
    protected String sendSupportMessage(Account customer, String subject) throws Exception {
        MvcResult res = mvc.perform(as(customer, multipart("/api/v1/support-messages")
                        .param("subject", subject).param("message", "Could you help me with this?")))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.reference");
    }

    private Long staffId(String email) {
        return jdbc.queryForObject("SELECT id FROM support_staff WHERE email = ?", Long.class, email);
    }
}
