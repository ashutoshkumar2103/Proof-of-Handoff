package com.handoffly.support;

import com.handoffly.notification.EmailMessage;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What each of the four plans gets from support, enforced by the backend whatever the client shows:
 * MONTHLY none; QUARTERLY Contact Support as a plain message; HALF_YEARLY adds tickets; YEARLY adds the
 * direct call. The plan is the only input — there is no per-feature switch to test.
 */
class SubscriptionSupportTest extends ApiTestBase {

    private static final String MESSAGES = "/api/v1/support-messages";
    private static final String TICKETS = "/api/v1/tickets";
    private static final String DESK = "/api/v1/support/tickets";

    private ResultActions postMessage(Account customer, String subject, String message) throws Exception {
        return mvc.perform(as(customer, multipart(MESSAGES).param("subject", subject).param("message", message)));
    }

    private ResultActions postTicket(Account customer) throws Exception {
        return mvc.perform(as(customer, post(TICKETS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Help\",\"category\":\"GENERAL\",\"description\":\"Please help\"}")));
    }

    private String deskJson(StaffAccount staff, String ticketCode) throws Exception {
        return mvc.perform(as(staff, get(DESK + "/" + ticketCode))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long ticketCountOnDesk(StaffAccount staff, Account customer) throws Exception {
        String json = mvc.perform(as(staff, get(DESK).param("accountCode", customer.accountCode())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.totalElements")).longValue();
    }

    // ------------------------------------------------------------------ what each plan shows the customer

    @Test
    void theCustomerAppIsToldExactlyWhatTheFinalMatrixSaysForEveryPlan() throws Exception {
        // plan, contact support, message, ticket, call, priority
        record Row(SubscriptionPlan plan, boolean contact, boolean message, boolean ticket, boolean call, String priority) {}
        List<Row> matrix = List.of(
                new Row(SubscriptionPlan.MONTHLY, false, false, false, false, "NORMAL"),
                new Row(SubscriptionPlan.QUARTERLY, true, true, false, false, "NORMAL"),
                new Row(SubscriptionPlan.HALF_YEARLY, true, true, true, false, "PRIORITY"),
                new Row(SubscriptionPlan.YEARLY, true, true, true, true, "HIGHEST"));

        for (Row row : matrix) {
            Account customer = register(row.plan());
            ResultActions me = mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.plan").value(row.plan().name()))
                    .andExpect(jsonPath("$.support.contactSupport").value(row.contact()))
                    .andExpect(jsonPath("$.support.message").value(row.message()))
                    .andExpect(jsonPath("$.support.ticket").value(row.ticket()))
                    .andExpect(jsonPath("$.support.call").value(row.call()))
                    .andExpect(jsonPath("$.support.priority").value(row.priority()));
            if (row.call()) {
                me.andExpect(jsonPath("$.support.supportPhone").value(SUPPORT_PHONE));   // from configuration
            } else {
                me.andExpect(jsonPath("$.support.supportPhone").doesNotExist());         // never sent to lower plans
            }
        }
    }

    @Test
    void thePublicPricingListsExactlyTheFourPlansWithTheirSupportAndNeverThePhone() throws Exception {
        mvc.perform(get("/api/v1/public/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].plan", contains("MONTHLY", "QUARTERLY", "HALF_YEARLY", "YEARLY")))
                .andExpect(jsonPath("$[0].support.contactSupport").value(false))
                .andExpect(jsonPath("$[1].support.contactSupport").value(true))
                .andExpect(jsonPath("$[1].support.ticket").value(false))
                .andExpect(jsonPath("$[2].support.ticket").value(true))
                .andExpect(jsonPath("$[2].support.call").value(false))
                .andExpect(jsonPath("$[3].support.call").value(true))
                .andExpect(jsonPath("$[3].support.priority").value("HIGHEST"))
                .andExpect(jsonPath("$[*].support.supportPhone", everyItem(nullValue())));
    }

    // ------------------------------------------------------------------ MONTHLY

    @Test
    void monthlyHasNoMessageTicketOrCallAccess() throws Exception {
        Account monthly = register();
        StaffAccount staff = registerStaff(SupportRole.MANAGER);

        postMessage(monthly, "Hello", "Anyone there?").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        postTicket(monthly).andExpect(status().isForbidden());
        mvc.perform(as(monthly, get(TICKETS))).andExpect(status().isForbidden());
        assertThat(ticketCountOnDesk(staff, monthly)).isZero();   // the refused attempts stored nothing
    }

    // ------------------------------------------------------------------ QUARTERLY

    @Test
    void quarterlyCanMessageSupportButHasNoTicketWorkflowAndNoCall() throws Exception {
        Account quarterly = register(SubscriptionPlan.QUARTERLY);

        String reference = sendSupportMessage(quarterly, "Cannot export");
        assertThat(reference).matches("TKT-\\d{2,}");

        // No ticket UI behind it: every customer ticket endpoint is refused by the backend.
        postTicket(quarterly).andExpect(status().isForbidden());
        mvc.perform(as(quarterly, get(TICKETS))).andExpect(status().isForbidden());
        mvc.perform(as(quarterly, get(TICKETS + "/" + reference))).andExpect(status().isForbidden());
        mvc.perform(as(quarterly, post(TICKETS + "/" + reference + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(quarterly, multipart(TICKETS + "/" + reference + "/attachments")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1})))).andExpect(status().isForbidden());
        mvc.perform(as(quarterly, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist());
    }

    @Test
    void aQuarterlyMessageReachesSupportAsATicketWithEverythingTheDeskNeeds() throws Exception {
        Account quarterly = register("Quinn Quarterly", SubscriptionPlan.QUARTERLY);
        StaffAccount staff = registerStaff(SupportRole.TICKET_AGENT);

        String reference = mvc.perform(as(quarterly, multipart(MESSAGES)
                        .param("subject", "  Export \n fails ").param("message", "It stops at 50%.")
                        .param("handoffReference", "av-3")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(reference, "$.reference");

        mvc.perform(as(staff, get(DESK + "/" + code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.contactMethod").value("MESSAGE"))
                .andExpect(jsonPath("$.ticket.accountCode").value(quarterly.accountCode()))
                .andExpect(jsonPath("$.ticket.customerName").value("Quinn Quarterly"))
                .andExpect(jsonPath("$.ticket.customerEmail").value(quarterly.email()))
                .andExpect(jsonPath("$.ticket.customerPhone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.ticket.plan").value("QUARTERLY"))
                .andExpect(jsonPath("$.ticket.priority").value("NORMAL"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.subject").value("Export fails"))
                .andExpect(jsonPath("$.ticket.handoffReference").value("AV-3"))
                .andExpect(jsonPath("$.description").value("It stops at 50%."));

        EmailMessage mail = emailSender.getLastMessage();
        assertThat(mail.to()).isEqualTo(SUPPORT_MAILBOX);
        assertThat(mail.textBody()).contains(code, "MESSAGE", quarterly.accountCode(), "NORMAL priority");
    }

    @Test
    void aSupportReplyToAQuarterlyMessageInvitesAnotherMessageNotATicketAnswer() throws Exception {
        Account quarterly = register(SubscriptionPlan.QUARTERLY);
        Account halfYearly = register(SubscriptionPlan.HALF_YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String message = sendSupportMessage(quarterly, "Question");
        String ticket = createTicket(halfYearly, "Question");

        mvc.perform(as(staff, post(DESK + "/" + message + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Here you go.\"}"))).andExpect(status().isCreated());
        EmailMessage toQuarterly = emailSender.getLastMessage();
        assertThat(toQuarterly.to()).isEqualTo(quarterly.email());
        assertThat(toQuarterly.textBody()).contains("Here you go.", "send us another message")
                .doesNotContain("choose this ticket");

        mvc.perform(as(staff, post(DESK + "/" + ticket + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Here you go.\"}"))).andExpect(status().isCreated());
        assertThat(emailSender.getLastMessage().textBody()).contains("choose this ticket");
    }

    @Test
    void aMessageNeedsASubjectAndTextAndTakesNoIdentityFromTheRequest() throws Exception {
        Account quarterly = register(SubscriptionPlan.QUARTERLY);
        Account other = register(SubscriptionPlan.QUARTERLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);

        postMessage(quarterly, "", "text").andExpect(status().isBadRequest());
        postMessage(quarterly, "subject", "  ").andExpect(status().isBadRequest());
        postMessage(quarterly, "s".repeat(201), "text").andExpect(status().isBadRequest());
        postMessage(quarterly, "subject", "t".repeat(5001)).andExpect(status().isBadRequest());
        mvc.perform(as(quarterly, multipart(MESSAGES).param("subject", "s").param("message", "m")
                .param("handoffReference", "x".repeat(21)))).andExpect(status().isBadRequest());
        assertThat(ticketCountOnDesk(staff, quarterly)).isZero();

        // Whoever the form claims to be, the message is from the signed-in account.
        String code = JsonPath.read(mvc.perform(as(quarterly, multipart(MESSAGES)
                        .param("subject", "Mine").param("message", "m")
                        .param("accountCode", other.accountCode()).param("plan", "YEARLY").param("email", other.email())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reference");
        mvc.perform(as(staff, get(DESK + "/" + code)))
                .andExpect(jsonPath("$.ticket.accountCode").value(quarterly.accountCode()))
                .andExpect(jsonPath("$.ticket.plan").value("QUARTERLY"));
        assertThat(ticketCountOnDesk(staff, other)).isZero();
    }

    @Test
    void aMessageCanCarryOneAttachmentAndABadFileMeansNoMessageAtAll() throws Exception {
        Account quarterly = register(SubscriptionPlan.QUARTERLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);

        String code = JsonPath.read(mvc.perform(as(quarterly, multipart(MESSAGES)
                        .file(new MockMultipartFile("file", "log.txt", "text/plain", "stack trace".getBytes(StandardCharsets.UTF_8)))
                        .param("subject", "With a file").param("message", "See the log")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reference");
        String detail = deskJson(staff, code);
        assertThat((String) JsonPath.read(detail, "$.attachments[0].originalFilename")).isEqualTo("log.txt");
        Number attachmentId = JsonPath.read(detail, "$.attachments[0].id");
        mvc.perform(as(staff, get(DESK + "/" + code + "/attachments/" + attachmentId + "/content")))
                .andExpect(status().isOk()).andExpect(content().string("stack trace"));

        long before = ticketCountOnDesk(staff, quarterly);
        mvc.perform(as(quarterly, multipart(MESSAGES)
                        .file(new MockMultipartFile("file", "virus.exe", "application/x-msdownload", new byte[]{1}))
                        .param("subject", "Bad file").param("message", "m")))
                .andExpect(status().isBadRequest());
        assertThat(ticketCountOnDesk(staff, quarterly)).isEqualTo(before);   // the refused file sent nothing
    }

    @Test
    void messagingIsForCustomersOnlyAndNeedsSignIn() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        mvc.perform(multipart(MESSAGES).param("subject", "s").param("message", "m")).andExpect(status().isUnauthorized());
        mvc.perform(as(staff, multipart(MESSAGES).param("subject", "s").param("message", "m")))
                .andExpect(status().isUnauthorized());   // a staff token is not a customer's
    }

    // ------------------------------------------------------------------ HALF_YEARLY and YEARLY

    @Test
    void halfYearlyHasTicketsButNoCall() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);

        String ticket = createTicket(customer, "Broken");
        mvc.perform(as(customer, get(TICKETS))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(customer, get(TICKETS + "/" + ticket))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.plan").value("HALF_YEARLY"));
        mvc.perform(as(customer, post(TICKETS + "/" + ticket + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"More info\"}"))).andExpect(status().isCreated());
        mvc.perform(as(customer, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist());
        sendSupportMessage(customer, "A message works too");   // Contact Support includes messaging on every plan that has it
    }

    @Test
    void yearlyHasTicketsAndTheDirectCallFromConfiguration() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);

        String ticket = createTicket(customer, "Urgent");
        mvc.perform(as(customer, get(TICKETS + "/" + ticket))).andExpect(status().isOk());
        mvc.perform(as(customer, post(TICKETS + "/" + ticket + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Still broken\"}"))).andExpect(status().isCreated());
        mvc.perform(as(customer, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.support.call").value(true))
                .andExpect(jsonPath("$.support.supportPhone").value(SUPPORT_PHONE));
    }

    // ------------------------------------------------------------------ priority on the support desk

    @Test
    void theDeskSeesEachTicketsPriorityFromThePlan() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.TICKET_AGENT);
        String quarterly = sendSupportMessage(register(SubscriptionPlan.QUARTERLY), "Q");
        String halfYearly = createTicket(register(SubscriptionPlan.HALF_YEARLY), "H");
        String yearly = createTicket(register(SubscriptionPlan.YEARLY), "Y");

        assertThat((String) JsonPath.read(deskJson(staff, quarterly), "$.ticket.priority")).isEqualTo("NORMAL");
        assertThat((String) JsonPath.read(deskJson(staff, halfYearly), "$.ticket.priority")).isEqualTo("PRIORITY");
        assertThat((String) JsonPath.read(deskJson(staff, yearly), "$.ticket.priority")).isEqualTo("HIGHEST");

        // "Priority only" is the plans with elevated priority — half-yearly and yearly, not quarterly.
        String listed = mvc.perform(as(staff, get(DESK).param("priorityOnly", "true"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(listed, "$.content[*].ticketCode");
        assertThat(codes).contains(halfYearly, yearly).doesNotContain(quarterly);
    }

    // ------------------------------------------------------------------ plan changes move the access

    @Test
    void aPlanChangeMovesTheCustomerStraightToTheNewAccessIncludingTheirEarlierMessage() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.QUARTERLY);
        String reference = sendSupportMessage(customer, "Sent while on quarterly");
        mvc.perform(as(customer, get(TICKETS + "/" + reference))).andExpect(status().isForbidden());

        mvc.perform(as(manager, put("/api/v1/support/customers/" + customer.accountCode() + "/plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromPlan\":\"QUARTERLY\",\"toPlan\":\"HALF_YEARLY\"}")))
                .andExpect(status().isOk());
        mvc.perform(as(customer, get(TICKETS + "/" + reference))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.contactMethod").value("MESSAGE"));

        mvc.perform(as(manager, put("/api/v1/support/customers/" + customer.accountCode() + "/plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromPlan\":\"HALF_YEARLY\",\"toPlan\":\"MONTHLY\"}")))
                .andExpect(status().isOk());
        postMessage(customer, "Now monthly", "m").andExpect(status().isForbidden());
        mvc.perform(as(customer, get(TICKETS + "/" + reference))).andExpect(status().isForbidden());
    }

    @Test
    void thereIsNoPerFeatureSwitchForSupportToFlip() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.MONTHLY);
        String profile = "/api/v1/support/customers/" + customer.accountCode();

        // Only the plan and the prefix can be changed; entitlement fields in a request change nothing.
        mvc.perform(as(manager, put(profile + "/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"MONTHLY\",\"ticket\":true,\"call\":true,\"contactSupport\":true}")))
                .andExpect(status().isConflict());
        mvc.perform(as(manager, put(profile + "/entitlements").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":true}"))).andExpect(status().is4xxClientError());
        mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(jsonPath("$.support.ticket").value(false))
                .andExpect(jsonPath("$.support.contactSupport").value(false));
        mvc.perform(as(manager, get(profile))).andExpect(jsonPath("$.entitlements.ticket").value(false))
                .andExpect(jsonPath("$.entitlements.contactSupport").value(false));
    }
}
