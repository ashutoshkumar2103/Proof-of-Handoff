package com.handoffly.support;

import com.handoffly.notification.EmailMessage;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ticket system end to end: who may open tickets, what is stored, who sees what, and how a ticket moves. */
class SupportTicketTest extends ApiTestBase {

    private static final String TICKETS = "/api/v1/tickets";
    private static final String DESK = "/api/v1/support/tickets";

    private record Counts(long open, long inProgress, long waiting, long priority) {}

    private static long number(String ticketCode) {
        return Long.parseLong(ticketCode.substring("TKT-".length()));
    }

    private static String ticketJson(String subject, String description) {
        return "{\"subject\":\"" + subject + "\",\"category\":\"TECHNICAL\",\"description\":\"" + description + "\"}";
    }

    private static MockMultipartFile textFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    private ResultActions postTicket(Account customer, String json) throws Exception {
        return mvc.perform(as(customer, post(TICKETS).contentType(MediaType.APPLICATION_JSON).content(json)));
    }

    private ResultActions customerReply(Account customer, String code, String text) throws Exception {
        return mvc.perform(as(customer, post(TICKETS + "/" + code + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + text + "\"}")));
    }

    private ResultActions supportReply(StaffAccount staff, String code, String text) throws Exception {
        return mvc.perform(as(staff, post(DESK + "/" + code + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + text + "\"}")));
    }

    private ResultActions tryStatus(StaffAccount staff, String code, String newStatus) throws Exception {
        return mvc.perform(as(staff, put(DESK + "/" + code + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + newStatus + "\"}")));
    }

    private void setStatus(StaffAccount staff, String code, String newStatus) throws Exception {
        tryStatus(staff, code, newStatus).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value(newStatus));
    }

    private String statusOf(StaffAccount staff, String code) throws Exception {
        String json = mvc.perform(as(staff, get(DESK + "/" + code))).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.ticket.status");
    }

    private Counts counts(StaffAccount staff) throws Exception {
        String json = mvc.perform(as(staff, get("/api/v1/support/dashboard"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Counts(((Number) JsonPath.read(json, "$.open")).longValue(),
                ((Number) JsonPath.read(json, "$.inProgress")).longValue(),
                ((Number) JsonPath.read(json, "$.waitingForCustomer")).longValue(),
                ((Number) JsonPath.read(json, "$.priorityCustomers")).longValue());
    }

    private List<String> deskCodes(StaffAccount staff, String query) throws Exception {
        String json = mvc.perform(as(staff, get(DESK + query))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.content[*].ticketCode");
    }

    // ------------------------------------------------------------------ plan entitlements

    @Test
    void monthlyCustomersHaveNoTicketAccessAtAll() throws Exception {
        Account monthly = register();
        String valid = ticketJson("Help", "Please help");

        postTicket(monthly, valid).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(as(monthly, get(TICKETS))).andExpect(status().isForbidden());
        mvc.perform(as(monthly, get(TICKETS + "/TKT-000001"))).andExpect(status().isForbidden());
        customerReply(monthly, "TKT-000001", "hello").andExpect(status().isForbidden());
        mvc.perform(as(monthly, multipart(TICKETS + "/TKT-000001/attachments").file(textFile("a.txt", new byte[]{1}))))
                .andExpect(status().isForbidden());
        mvc.perform(as(monthly, get(TICKETS + "/TKT-000001/attachments/1/content"))).andExpect(status().isForbidden());

        // The entitlement is read fresh on every call: upgrade and it works at once...
        setPlan(monthly, SubscriptionPlan.HALF_YEARLY);
        mvc.perform(as(monthly, get(TICKETS))).andExpect(jsonPath("$.totalElements").value(0));   // the refused attempt stored nothing
        postTicket(monthly, valid).andExpect(status().isCreated());
        // ...and downgrade, and it stops again, even for tickets they already have.
        setPlan(monthly, SubscriptionPlan.MONTHLY);
        mvc.perform(as(monthly, get(TICKETS))).andExpect(status().isForbidden());
    }

    @Test
    void halfYearlyAndYearlyCustomersCanOpenTickets() throws Exception {
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY)) {
            Account customer = register(plan);
            postTicket(customer, ticketJson("Question from " + plan, "Hello")).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.plan").value(plan.name()));
        }
    }

    // ------------------------------------------------------------------ creating a ticket

    @Test
    void aNewTicketCarriesTheAccountsIdentityAndNotifiesTheSupportMailbox() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);

        String body = postTicket(customer, """
                {"subject":"Cannot upload photo","category":"TECHNICAL",
                 "description":"The upload button does nothing.","handoffReference":" av-3 "}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.ticketCode").value(matchesPattern("TKT-\\d{2,}")))
                .andExpect(jsonPath("$.ticket.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.ticket.customerName").value("Test Customer"))
                .andExpect(jsonPath("$.ticket.customerEmail").value(customer.email()))
                .andExpect(jsonPath("$.ticket.customerPhone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.ticket.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.ticket.contactMethod").value("TICKET"))
                .andExpect(jsonPath("$.ticket.category").value("TECHNICAL"))
                .andExpect(jsonPath("$.ticket.subject").value("Cannot upload photo"))
                .andExpect(jsonPath("$.ticket.handoffReference").value("AV-3"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.messageCount").value(0))
                .andExpect(jsonPath("$.ticket.createdAt").exists())
                .andExpect(jsonPath("$.ticket.updatedAt").exists())
                .andExpect(jsonPath("$.description").value("The upload button does nothing."))
                .andExpect(jsonPath("$.messages.length()").value(0))
                .andExpect(jsonPath("$.attachments.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.ticket.ticketCode");

        EmailMessage mail = emailSender.getLastMessage();
        assertThat(mail.to()).isEqualTo(SUPPORT_MAILBOX);
        assertThat(mail.subject()).isEqualTo("[" + code + "] New ticket: Cannot upload photo");
        assertThat(mail.textBody()).contains(code, customer.accountCode(), "Test Customer", customer.email(),
                CUSTOMER_PHONE, "HALF_YEARLY", "TECHNICAL", "AV-3", "The upload button does nothing.");
    }

    @Test
    void theTicketPhoneDefaultsToTheAccountsAndCanBeGivenPerTicket() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        postTicket(customer, "{\"subject\":\"Call me\",\"category\":\"GENERAL\",\"description\":\"Urgent\",\"phone\":\"+1 555 9999\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.customerPhone").value("+1 555 9999"));
        postTicket(customer, "{\"subject\":\"No phone given\",\"category\":\"GENERAL\",\"description\":\"Urgent\",\"phone\":\"  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.customerPhone").value(CUSTOMER_PHONE));
        // The account's own phone is not rewritten by a ticket.
        assertThat(users.findById(customer.id()).orElseThrow().getPhone()).isEqualTo(CUSTOMER_PHONE);
    }

    @Test
    void whoYouAreIsNeverTakenFromTheRequest() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        Account victim = register(SubscriptionPlan.HALF_YEARLY);
        postTicket(customer, "{\"subject\":\"Spoof\",\"category\":\"GENERAL\",\"description\":\"x\","
                + "\"accountCode\":\"" + victim.accountCode() + "\",\"customerEmail\":\"" + victim.email() + "\","
                + "\"status\":\"CLOSED\",\"ticketCode\":\"TKT-999999\",\"plan\":\"YEARLY\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.ticket.customerEmail").value(customer.email()))
                .andExpect(jsonPath("$.ticket.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.ticketCode").value(not("TKT-999999")));
        mvc.perform(as(victim, get(TICKETS))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void ticketIdsAreSequentialAndNeverRepeat() throws Exception {
        Account first = register(SubscriptionPlan.HALF_YEARLY);
        Account second = register(SubscriptionPlan.YEARLY);

        String t1 = createTicket(first, "First");
        String t2 = createTicket(second, "Second");
        String t3 = createTicket(first, "Third");
        assertThat(number(t2)).isEqualTo(number(t1) + 1);
        assertThat(number(t3)).isEqualTo(number(t2) + 1);
    }

    @Test
    void concurrentTicketsNeverShareAnId() throws Exception {
        int threads = 6;
        List<Account> customers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            customers.add(register(SubscriptionPlan.HALF_YEARLY));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Account customer = customers.get(i);
                int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return createTicket(customer, "Parallel " + n);
                }));
            }
            go.countDown();

            TreeSet<String> codes = new TreeSet<>();
            for (Future<String> result : results) {
                codes.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(codes).hasSize(threads);
            assertThat(number(codes.last()) - number(codes.first())).isEqualTo(threads - 1);   // consecutive: no gaps either
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ticketInputIsValidatedAndNothingIsStoredWhenItIsNot() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);

        List<String> invalid = List.of(
                "{\"category\":\"TECHNICAL\",\"description\":\"x\"}",                                       // no subject
                "{\"subject\":\"   \",\"category\":\"TECHNICAL\",\"description\":\"x\"}",                   // blank subject
                "{\"subject\":\"" + "s".repeat(201) + "\",\"category\":\"TECHNICAL\",\"description\":\"x\"}",
                "{\"subject\":\"s\",\"description\":\"x\"}",                                                // no category
                "{\"subject\":\"s\",\"category\":\"NOPE\",\"description\":\"x\"}",                          // unknown category
                "{\"subject\":\"s\",\"category\":\"TECHNICAL\"}",                                           // no description
                "{\"subject\":\"s\",\"category\":\"TECHNICAL\",\"description\":\"  \"}",
                "{\"subject\":\"s\",\"category\":\"TECHNICAL\",\"description\":\"" + "d".repeat(5001) + "\"}",
                "{\"subject\":\"s\",\"category\":\"TECHNICAL\",\"description\":\"x\",\"handoffReference\":\"" + "R".repeat(21) + "\"}",
                "{\"subject\":\"s\",\"category\":\"TECHNICAL\",\"description\":\"x\",\"phone\":\"" + "1".repeat(41) + "\"}",
                "this is not json", "");
        for (String body : invalid) {
            postTicket(customer, body).andExpect(status().isBadRequest());
        }
        mvc.perform(as(customer, get(TICKETS))).andExpect(jsonPath("$.totalElements").value(0));

        // The limits themselves are allowed.
        postTicket(customer, "{\"subject\":\"" + "s".repeat(200) + "\",\"category\":\"BILLING\",\"description\":\""
                + "d".repeat(5000) + "\",\"handoffReference\":\"" + "R".repeat(20) + "\",\"phone\":\"" + "1".repeat(40) + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void aSubjectIsAlwaysKeptOnOneLineSoItCannotBreakAnEmailHeader() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        postTicket(customer, "{\"subject\":\"  Line one\\nLine   two\\r\\nBcc: attacker@example.test \","
                + "\"category\":\"GENERAL\",\"description\":\"x\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.subject").value("Line one Line two Bcc: attacker@example.test"));
        assertThat(emailSender.getLastMessage().subject()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aMailOutageNeverLosesATicketOrAReply() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        EmailMessage before = emailSender.getLastMessage();

        emailSender.setFailing(true);
        try {
            String code = createTicket(customer, "During an outage");
            customerReply(customer, code, "Still here").andExpect(status().isCreated());
            supportReply(staff, code, "Noted").andExpect(status().isCreated());
            mvc.perform(as(customer, get(TICKETS + "/" + code)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages.length()").value(2));
        } finally {
            emailSender.setFailing(false);
        }
        assertThat(emailSender.getLastMessage()).isSameAs(before);   // nothing was delivered, yet nothing was lost
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void aCustomerOnlyEverSeesAndTouchesTheirOwnTickets() throws Exception {
        Account mine = register(SubscriptionPlan.HALF_YEARLY);
        Account theirs = register(SubscriptionPlan.YEARLY);
        String myTicket = createTicket(mine, "Mine");
        String theirTicket = createTicket(theirs, "Theirs");

        String list = mvc.perform(as(mine, get(TICKETS))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(list, "$.content[*].ticketCode")).containsExactly(myTicket);
        assertThat((List<String>) JsonPath.read(list, "$.content[*].accountCode")).containsOnly(mine.accountCode());

        // Someone else's ticket is "not found" — indistinguishable from one that does not exist.
        mvc.perform(as(mine, get(TICKETS + "/" + theirTicket))).andExpect(status().isNotFound());
        customerReply(mine, theirTicket, "Let me in").andExpect(status().isNotFound());
        mvc.perform(as(mine, multipart(TICKETS + "/" + theirTicket + "/attachments").file(textFile("x.txt", new byte[]{1}))))
                .andExpect(status().isNotFound());
        mvc.perform(as(mine, get(TICKETS + "/" + theirTicket + "/attachments/1/content"))).andExpect(status().isNotFound());
        mvc.perform(as(mine, get(TICKETS + "/TKT-999999"))).andExpect(status().isNotFound());

        // Their ticket is untouched, and nothing is open without signing in.
        mvc.perform(as(theirs, get(TICKETS + "/" + theirTicket))).andExpect(jsonPath("$.messages.length()").value(0));
        mvc.perform(get(TICKETS)).andExpect(status().isUnauthorized());
        mvc.perform(get(TICKETS + "/" + myTicket)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ the conversation

    @Test
    void repliesAreRecordedInOrderAndEmailedToTheOtherSide() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String code = createTicket(customer, "Cannot upload");

        customerReply(customer, code, "  More detail: it fails on PNG files.  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].author").value("CUSTOMER"))
                .andExpect(jsonPath("$.messages[0].authorName").value("Test Customer"))
                .andExpect(jsonPath("$.messages[0].body").value("More detail: it fails on PNG files."))
                .andExpect(jsonPath("$.ticket.messageCount").value(1));
        EmailMessage toSupport = emailSender.getLastMessage();
        assertThat(toSupport.to()).isEqualTo(SUPPORT_MAILBOX);
        assertThat(toSupport.subject()).isEqualTo("[" + code + "] Customer reply: Cannot upload");
        assertThat(toSupport.textBody()).contains(code, customer.accountCode(), "More detail: it fails on PNG files.");

        supportReply(staff, code, "Thanks - please try again now.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].author").value("SUPPORT"))
                .andExpect(jsonPath("$.messages[1].authorName").value("Support Agent"))
                .andExpect(jsonPath("$.ticket.messageCount").value(2));
        EmailMessage toCustomer = emailSender.getLastMessage();
        assertThat(toCustomer.to()).isEqualTo(customer.email());
        assertThat(toCustomer.subject()).isEqualTo("[" + code + "] Reply from support: Cannot upload");
        assertThat(toCustomer.textBody()).contains("Hello Test Customer,", code, "Thanks - please try again now.");
        assertThat(toCustomer.textBody()).doesNotContain(staff.email());   // the agent's address stays private

        // Both sides see the same conversation, in order, with the original request untouched.
        for (ResultActions view : List.of(mvc.perform(as(customer, get(TICKETS + "/" + code))),
                mvc.perform(as(staff, get(DESK + "/" + code))))) {
            view.andExpect(jsonPath("$.description").value("The upload button does nothing."))
                    .andExpect(jsonPath("$.messages[*].author", contains("CUSTOMER", "SUPPORT")));
        }
    }

    @Test
    void repliesAreValidated() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String code = createTicket(customer, "Validation");

        for (String body : List.of("{}", "{\"body\":null}", "{\"body\":\"   \"}", "{\"body\":\"" + "x".repeat(5001) + "\"}",
                "not json", "")) {
            mvc.perform(as(customer, post(TICKETS + "/" + code + "/messages")
                    .contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isBadRequest());
            mvc.perform(as(staff, post(DESK + "/" + code + "/messages")
                    .contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(customer, get(TICKETS + "/" + code))).andExpect(jsonPath("$.messages.length()").value(0));
        customerReply(customer, code, "x".repeat(5000)).andExpect(status().isCreated());   // the limit itself is fine
    }

    @Test
    void theStatusFollowsTheConversationWithoutHiddenSurprises() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String code = createTicket(customer, "Status flow");
        assertThat(statusOf(staff, code)).isEqualTo("OPEN");

        setStatus(staff, code, "IN_PROGRESS");
        supportReply(staff, code, "Looking into it").andExpect(status().isCreated());
        assertThat(statusOf(staff, code)).isEqualTo("IN_PROGRESS");   // a reply alone changes nothing

        setStatus(staff, code, "WAITING_FOR_CUSTOMER");
        customerReply(customer, code, "Here you go").andExpect(jsonPath("$.ticket.status").value("OPEN"));   // back in the queue

        setStatus(staff, code, "RESOLVED");
        customerReply(customer, code, "Actually it is still broken").andExpect(jsonPath("$.ticket.status").value("OPEN"));

        setStatus(staff, code, "IN_PROGRESS");
        customerReply(customer, code, "Any news?").andExpect(jsonPath("$.ticket.status").value("IN_PROGRESS"));
        setStatus(staff, code, "IN_PROGRESS");   // the same status again is harmless

        // The customer can read the status but has no way to set it.
        mvc.perform(as(customer, get(TICKETS + "/" + code))).andExpect(jsonPath("$.ticket.status").value("IN_PROGRESS"));
        mvc.perform(as(customer, put(TICKETS + "/" + code + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}"))).andExpect(status().is4xxClientError());
        assertThat(statusOf(staff, code)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aClosedTicketIsFinal() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String code = createTicket(customer, "To be closed");

        setStatus(staff, code, "CLOSED");   // any open ticket can be closed

        customerReply(customer, code, "One more thing").andExpect(status().isConflict());
        supportReply(staff, code, "Sorry, closed").andExpect(status().isConflict());
        tryStatus(staff, code, "OPEN").andExpect(status().isConflict());
        tryStatus(staff, code, "IN_PROGRESS").andExpect(status().isConflict());
        mvc.perform(as(customer, multipart(TICKETS + "/" + code + "/attachments").file(textFile("late.txt", new byte[]{1}))))
                .andExpect(status().isConflict());
        tryStatus(staff, code, "CLOSED").andExpect(status().isOk());   // saying it again changes nothing

        // It can still be read, and nothing was added.
        mvc.perform(as(customer, get(TICKETS + "/" + code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value("CLOSED"))
                .andExpect(jsonPath("$.messages.length()").value(0))
                .andExpect(jsonPath("$.attachments.length()").value(0));
    }

    // ------------------------------------------------------------------ the support desk

    @Test
    void theDashboardCountsFollowTheTickets() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Counts base = counts(staff);
        Account yearly = register(SubscriptionPlan.YEARLY);
        Account quarterly = register(SubscriptionPlan.QUARTERLY);   // normal priority

        String priorityOne = createTicket(yearly, "Priority one");
        String standard = sendSupportMessage(quarterly, "Standard");
        assertThat(counts(staff)).isEqualTo(new Counts(base.open() + 2, base.inProgress(), base.waiting(), base.priority() + 1));

        String priorityTwo = createTicket(yearly, "Priority two");   // same customer: still ONE priority customer
        assertThat(counts(staff)).isEqualTo(new Counts(base.open() + 3, base.inProgress(), base.waiting(), base.priority() + 1));

        setStatus(staff, priorityOne, "IN_PROGRESS");
        setStatus(staff, standard, "WAITING_FOR_CUSTOMER");
        assertThat(counts(staff)).isEqualTo(
                new Counts(base.open() + 1, base.inProgress() + 1, base.waiting() + 1, base.priority() + 1));

        // Resolved and closed tickets no longer need attention; once none of a priority customer's do, they leave the tile.
        setStatus(staff, priorityOne, "RESOLVED");
        assertThat(counts(staff).priority()).isEqualTo(base.priority() + 1);   // priorityTwo is still active
        setStatus(staff, priorityTwo, "CLOSED");
        assertThat(counts(staff)).isEqualTo(new Counts(base.open(), base.inProgress(), base.waiting() + 1, base.priority()));
    }

    @Test
    void supportCanFilterTicketsByStatusPlanAndCustomerNewestActivityFirst() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account yearly = register(SubscriptionPlan.YEARLY);
        Account quarterly = register(SubscriptionPlan.QUARTERLY);   // normal priority
        String y1 = createTicket(yearly, "A");
        String y2 = createTicket(yearly, "B");
        String h1 = sendSupportMessage(quarterly, "C");
        setStatus(staff, y1, "IN_PROGRESS");

        String yearlyOnly = "?accountCode=" + yearly.accountCode();
        assertThat(deskCodes(staff, yearlyOnly)).containsExactly(y1, y2);   // y1 was touched last
        assertThat(deskCodes(staff, yearlyOnly + "&status=IN_PROGRESS")).containsExactly(y1);
        assertThat(deskCodes(staff, yearlyOnly + "&status=OPEN")).containsExactly(y2);
        assertThat(deskCodes(staff, yearlyOnly + "&status=RESOLVED")).isEmpty();
        // Several statuses at once — e.g. "needs attention" = open, in progress and waiting.
        assertThat(deskCodes(staff, yearlyOnly + "&status=OPEN&status=IN_PROGRESS&status=WAITING_FOR_CUSTOMER"))
                .containsExactly(y1, y2);
        assertThat(deskCodes(staff, yearlyOnly + "&status=OPEN&status=WAITING_FOR_CUSTOMER")).containsExactly(y2);
        assertThat(deskCodes(staff, yearlyOnly + "&status=RESOLVED&status=CLOSED")).isEmpty();

        assertThat(deskCodes(staff, yearlyOnly + "&priorityOnly=true")).containsExactly(y1, y2);
        assertThat(deskCodes(staff, "?priorityOnly=true&accountCode=" + quarterly.accountCode())).isEmpty();
        assertThat(deskCodes(staff, "?priorityOnly=false&accountCode=" + quarterly.accountCode())).containsExactly(h1);

        // The most recently touched ticket of all comes first.
        supportReply(staff, h1, "Hello").andExpect(status().isCreated());
        assertThat(deskCodes(staff, "").getFirst()).isEqualTo(h1);

        // Odd input is data, not code, and bad values are refused rather than crashing.
        mvc.perform(as(staff, get(DESK).param("accountCode", "' OR '1'='1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        assertThat(deskCodes(staff, "?accountCode=CUS-999999")).isEmpty();
        mvc.perform(as(staff, get(DESK).param("status", "BOGUS"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        mvc.perform(as(staff, get(DESK).param("priorityOnly", "maybe"))).andExpect(status().isBadRequest());
        mvc.perform(as(staff, get(DESK).param("size", "1000"))).andExpect(jsonPath("$.size").value(100));
        // A caller-chosen sort cannot reach private fields.
        mvc.perform(as(staff, get(DESK).param("sort", "account.passwordHash,asc"))).andExpect(status().isOk());
    }

    @Test
    void supportSeesWhoAndWhatButNeverTheCustomersHandoffs() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        createHandoff(customer);   // titled "Laptop handout", recipient hire@example.test, item serial SN-1
        String code = createTicket(customer, "Handoff question");

        String body = mvc.perform(as(staff, get(DESK + "/" + code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.ticketCode").value(code))
                .andExpect(jsonPath("$.ticket.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.ticket.customerName").value("Test Customer"))
                .andExpect(jsonPath("$.ticket.customerEmail").value(customer.email()))
                .andExpect(jsonPath("$.ticket.customerPhone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.ticket.plan").value("YEARLY"))
                .andExpect(jsonPath("$.ticket.contactMethod").value("TICKET"))
                .andExpect(jsonPath("$.ticket.subject").value("Handoff question"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.handoffReference").value("AV-3"))   // just the reference the customer typed
                .andExpect(jsonPath("$.description").value("The upload button does nothing."))
                .andExpect(jsonPath("$.ticket.createdAt").exists())
                .andExpect(jsonPath("$.ticket.updatedAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Laptop handout", "hire@example.test", "SN-1", "passwordHash");

        String list = mvc.perform(as(staff, get(DESK).param("accountCode", customer.accountCode())))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("Laptop handout", "hire@example.test", "SN-1", "passwordHash");
    }

    @Test
    void unknownTicketsAndMalformedRequestsAreRefusedCleanly() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String code = createTicket(customer, "Odd requests");

        mvc.perform(as(staff, get(DESK + "/TKT-999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        tryStatus(staff, "TKT-999999", "CLOSED").andExpect(status().isNotFound());
        supportReply(staff, "TKT-999999", "Hello?").andExpect(status().isNotFound());

        tryStatus(staff, code, "BOGUS").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("malformed_request"));
        mvc.perform(as(staff, put(DESK + "/" + code + "/status").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(as(staff, get(DESK + "/" + code + "/attachments/abc/content"))).andExpect(status().isBadRequest());
        assertThat(statusOf(staff, code)).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ attachments

    @Test
    void attachmentsAreAddedViewedAndKeptPrivate() throws Exception {
        Account owner = register(SubscriptionPlan.YEARLY);
        Account other = register(SubscriptionPlan.YEARLY);
        Account monthly = register();
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String code = createTicket(owner, "With a file");
        String upload = TICKETS + "/" + code + "/attachments";
        byte[] bytes = "Stack trace line 1\nline 2".getBytes(StandardCharsets.UTF_8);

        // A path in the filename is stripped; only the plain name is kept.
        int id = JsonPath.read(mvc.perform(as(owner, multipart(upload).file(textFile("..\\logs\\error-log.txt", bytes))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("error-log.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Both sides see it on the ticket and can download exactly what was uploaded.
        mvc.perform(as(owner, get(TICKETS + "/" + code))).andExpect(jsonPath("$.attachments[0].originalFilename").value("error-log.txt"));
        mvc.perform(as(staff, get(DESK + "/" + code))).andExpect(jsonPath("$.attachments[0].sizeBytes").value(bytes.length));
        for (ResultActions download : List.of(
                mvc.perform(as(owner, get(TICKETS + "/" + code + "/attachments/" + id + "/content"))),
                mvc.perform(as(staff, get(DESK + "/" + code + "/attachments/" + id + "/content"))))) {
            byte[] received = download.andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("attachment")))
                    .andExpect(header().string("Content-Disposition", containsString("error-log.txt")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(received).isEqualTo(bytes);
        }

        // Nobody else gets near it.
        mvc.perform(as(other, get(TICKETS + "/" + code + "/attachments/" + id + "/content"))).andExpect(status().isNotFound());
        mvc.perform(as(other, multipart(upload).file(textFile("x.txt", bytes)))).andExpect(status().isNotFound());
        mvc.perform(as(monthly, get(TICKETS + "/" + code + "/attachments/" + id + "/content"))).andExpect(status().isForbidden());
        mvc.perform(as(monthly, get(DESK + "/" + code + "/attachments/" + id + "/content"))).andExpect(status().isUnauthorized());
        mvc.perform(get(TICKETS + "/" + code + "/attachments/" + id + "/content")).andExpect(status().isUnauthorized());
        // An attachment id from another ticket does not work on this one.
        String otherCode = createTicket(other, "Someone else's");
        mvc.perform(as(other, get(TICKETS + "/" + otherCode + "/attachments/" + id + "/content"))).andExpect(status().isNotFound());
    }

    @Test
    void ticketAttachmentsFollowTheSameUploadRulesAsHandoffAttachments() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        String upload = TICKETS + "/" + createTicket(owner, "Rules") + "/attachments";

        mvc.perform(as(owner, multipart(upload).file(new MockMultipartFile("file", "virus.exe", "application/x-msdownload", new byte[]{1}))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(owner, multipart(upload).file(textFile("empty.txt", new byte[0])))).andExpect(status().isBadRequest());
        mvc.perform(as(owner, multipart(upload).file(textFile("big.txt", new byte[5 * 1024 * 1024 + 1])))).andExpect(status().isBadRequest());
        mvc.perform(as(owner, multipart(upload))).andExpect(status().isBadRequest());   // no file part at all
        mvc.perform(as(owner, multipart(upload).file(new MockMultipartFile("file", "photo.png", "image/png",
                        new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0}))))
                .andExpect(status().isCreated());   // an allowed type with matching content is accepted
    }
}
