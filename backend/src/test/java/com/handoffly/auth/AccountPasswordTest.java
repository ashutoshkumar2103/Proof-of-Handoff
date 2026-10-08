package com.handoffly.auth;

import com.handoffly.notification.EmailMessage;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer's own account: editing their details, changing the password (current one required), and the
 * forgot/reset flow (no current password; a one-time, expiring link by email). Both end the other sessions.
 */
class AccountPasswordTest extends ApiTestBase {

    private static final String NEW_PASSWORD = "a-brand-new-passphrase";
    private static final Pattern RESET_LINK = Pattern.compile("/reset-password/([A-Za-z0-9_-]+)");

    private ResultActions changePassword(Account who, String current, String next, String confirm) throws Exception {
        return mvc.perform(as(who, post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content(json("currentPassword", current, "newPassword", next, "confirmPassword", confirm))));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email)));
    }

    private ResultActions reset(String token, String next, String confirm) throws Exception {
        return mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json("token", token, "newPassword", next, "confirmPassword", confirm)));
    }

    private ResultActions loginAttempt(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password)));
    }

    private ResultActions me(Account who) throws Exception {
        return mvc.perform(as(who, get("/api/v1/auth/me")));
    }

    private static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    /** The raw token inside the reset link of the most recent email. */
    private String tokenFromLastEmail() {
        EmailMessage mail = emailSender.getLastMessage();
        Matcher m = RESET_LINK.matcher(mail.textBody());
        assertThat(m.find()).as("a reset link in the email").isTrue();
        return m.group(1);
    }

    // ------------------------------------------------------------------ the profile

    @Test
    void aCustomerEditsTheirOwnDetailsButNothingElseAboutTheAccount() throws Exception {
        Account customer = register("Original Name", null);
        String prefix = prefixOf(customer);

        mvc.perform(as(customer, put("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  New Name  \",\"organization\":\"Acme\",\"phone\":\"+91 90000 22222\","
                                + "\"accountCode\":\"CUS-999\",\"email\":\"hacker@example.test\",\"plan\":\"YEARLY\",\"handoffPrefix\":\"ZZ\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.organization").value("Acme"))
                .andExpect(jsonPath("$.phone").value("+91 90000 22222"))
                // The Account ID, email, plan and prefix are not editable: sending them changes nothing.
                .andExpect(jsonPath("$.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.email").value(customer.email()))
                .andExpect(jsonPath("$.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.handoffPrefix").value(prefix));

        // An empty organization or phone clears it; a blank name is refused.
        mvc.perform(as(customer, put("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Newer\",\"organization\":\"\",\"phone\":\"  \"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organization").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(as(customer, put("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  \"}"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ change password (current one required)

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsOutEveryOtherSession() throws Exception {
        Account customer = register();
        Account otherDevice = login(customer.email());   // a second session of the same customer
        me(customer).andExpect(status().isOk());
        me(otherDevice).andExpect(status().isOk());

        String body = changePassword(customer, PASSWORD, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Account renewed = new Account(customer.id(), customer.accountCode(), customer.email(), JsonPath.read(body, "$.token"));

        me(customer).andExpect(status().isUnauthorized());      // the token used to change it has ended...
        me(otherDevice).andExpect(status().isUnauthorized());   // ...so has every other session...
        me(renewed).andExpect(status().isOk());                 // ...but the caller gets a fresh one and carries on
        loginAttempt(customer.email(), PASSWORD).andExpect(status().isUnauthorized());
        loginAttempt(customer.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordOrAnUnacceptableNewOneChangesNothing() throws Exception {
        Account customer = register();

        // A wrong current password is a 400 (a 401 would make the apps think the session ended) and is not a sign-out.
        changePassword(customer, "not-my-password", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("current password is not correct")));
        changePassword(customer, PASSWORD, NEW_PASSWORD, "something-else-entirely").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("do not match")));
        changePassword(customer, PASSWORD, "short", "short").andExpect(status().isBadRequest());
        changePassword(customer, PASSWORD, PASSWORD, PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("different")));
        String tooLong = "p".repeat(PasswordPolicy.MAX_BYTES + 1);
        changePassword(customer, PASSWORD, tooLong, tooLong).andExpect(status().isBadRequest());
        changePassword(customer, "", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest());

        me(customer).andExpect(status().isOk());                                  // still signed in
        loginAttempt(customer.email(), PASSWORD).andExpect(status().isOk());      // still the old password
    }

    @Test
    void changingThePasswordNeedsASignedInCustomer() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD, "confirmPassword", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
        StaffAccount staff = registerStaff(com.handoffly.support.staff.SupportRole.ADMIN);
        mvc.perform(as(staff, post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", STAFF_PASSWORD, "newPassword", NEW_PASSWORD, "confirmPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());   // a staff token is not a customer's
    }

    @Test
    void aDisabledAccountsExistingTokenStopsWorking() throws Exception {
        Account customer = register();
        me(customer).andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", customer.id());
        me(customer).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ forgot / reset password (no current one)

    @Test
    void askingForAResetLinkSaysTheSameWhetherOrNotTheAccountExists() throws Exception {
        Account customer = register();
        emailSender.setFailing(false);

        String known = forgot(customer.email()).andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();
        EmailMessage sent = emailSender.getLastMessage();
        assertThat(sent.to()).isEqualTo(customer.email());
        assertThat(sent.subject()).contains("Reset your HandOffly password");
        assertThat(sent.textBody()).contains(customer.accountCode(), "/reset-password/", "30 minutes");

        EmailMessage before = emailSender.getLastMessage();
        String unknown = forgot("nobody-" + System.nanoTime() + "@example.test").andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();
        assertThat(emailSender.getLastMessage()).isSameAs(before);   // nothing was sent to a stranger
        assertThat(unknown).isEqualTo(known);                          // and the two answers are identical
        forgot("not-an-email").andExpect(status().isBadRequest());     // only a malformed address is refused
    }

    @Test
    void theResetLinkSetsANewPasswordWithoutTheOldOneSignsEveryoneOutAndWorksOnce() throws Exception {
        Account customer = register();
        forgot(customer.email()).andExpect(status().isNoContent());
        String token = tokenFromLastEmail();

        reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());   // no current password anywhere

        loginAttempt(customer.email(), PASSWORD).andExpect(status().isUnauthorized());
        loginAttempt(customer.email(), NEW_PASSWORD).andExpect(status().isOk());
        me(customer).andExpect(status().isUnauthorized());   // the session from before the reset has ended
        // One time only, whatever is sent the second time.
        reset(token, "yet-another-password-1", "yet-another-password-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("invalid or has expired")));
        loginAttempt(customer.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aBadNewPasswordDoesNotUseUpTheLink() throws Exception {
        Account customer = register();
        forgot(customer.email());
        String token = tokenFromLastEmail();

        reset(token, NEW_PASSWORD, "different").andExpect(status().isBadRequest());
        reset(token, "short", "short").andExpect(status().isBadRequest());
        reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());   // still good afterwards
    }

    @Test
    void anExpiredOrMadeUpLinkIsRefusedWithTheSameMessage() throws Exception {
        Account customer = register();
        forgot(customer.email());
        String token = tokenFromLastEmail();
        jdbc.update("UPDATE password_reset_token SET expires_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP)");

        String expired = reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String unknown = reset("no-such-token", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(expired, "$.detail")).isEqualTo(JsonPath.read(unknown, "$.detail"));
        reset("", NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest());
        loginAttempt(customer.email(), PASSWORD).andExpect(status().isOk());   // nothing changed
    }

    @Test
    void onlyTheNewestLinkWorksAndOnlyItsHashIsStored() throws Exception {
        Account customer = register();
        forgot(customer.email());
        String first = tokenFromLastEmail();
        forgot(customer.email());
        String second = tokenFromLastEmail();
        assertThat(second).isNotEqualTo(first);

        reset(first, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest());   // retired by asking again
        assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_token WHERE user_id = ?", String.class, customer.id()))
                .doesNotContain(first, second).allMatch(h -> h.length() == 64);
        reset(second, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void aDisabledAccountGetsNoResetLinkAndCannotUseOne() throws Exception {
        Account customer = register();
        forgot(customer.email());
        String token = tokenFromLastEmail();
        jdbc.update("UPDATE app_user SET enabled = FALSE WHERE id = ?", customer.id());

        EmailMessage before = emailSender.getLastMessage();
        forgot(customer.email()).andExpect(status().isNoContent());
        assertThat(emailSender.getLastMessage()).isSameAs(before);
        reset(token, NEW_PASSWORD, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void aMailOutageNeverShowsInTheAnswer() throws Exception {
        Account customer = register();
        emailSender.setFailing(true);
        try {
            forgot(customer.email()).andExpect(status().isNoContent()).andExpect(content().string(""));
        } finally {
            emailSender.setFailing(false);
        }
    }
}
