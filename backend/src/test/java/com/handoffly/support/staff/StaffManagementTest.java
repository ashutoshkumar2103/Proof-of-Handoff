package com.handoffly.support.staff;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin-only staff management: create, search, change role, deactivate and reactivate — every change audited. */
class StaffManagementTest extends ApiTestBase {

    private static final String STAFF = "/api/v1/support/staff";
    private static final String NEW_PASSWORD = "initial-passw0rd-1";

    private static String uniqueEmail() {
        return "managed-" + UUID.randomUUID().toString().substring(0, 10) + "@support.example.test";
    }

    private ResultActions createStaff(Bearer who, String name, String email, String password, String role) throws Exception {
        return mvc.perform(as(who, post(STAFF).contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}")));
    }

    private ResultActions changeRole(Bearer who, String code, String from, String to, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(as(who, put(STAFF + "/" + code + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromRole\":\"" + from + "\",\"toRole\":\"" + to + "\"" + reasonJson + "}")));
    }

    private ResultActions setActive(Bearer who, String code, boolean active, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(as(who, put(STAFF + "/" + code + "/active").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":" + active + reasonJson + "}")));
    }

    /** A staff member created straight through provisioning, plus how to sign in as them. */
    private SupportStaff provisioned(SupportRole role) {
        return staffService.provision("Provisioned " + role, uniqueEmail(), STAFF_PASSWORD, role);
    }

    private List<Map<String, Object>> auditFor(String staffCode) {
        return jdbc.queryForList("SELECT e.event_type, e.previous_value, e.new_value, e.reason, a.staff_code AS actor "
                + "FROM support_audit_event e JOIN support_staff a ON a.id = e.staff_id "
                + "JOIN support_staff t ON t.id = e.target_staff_id WHERE t.staff_code = ? ORDER BY e.id", staffCode);
    }

    // ------------------------------------------------------------------ create

    @Test
    void anAdminCreatesManagersAndTicketAgentsWhoCanThenSignIn() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        String email = uniqueEmail();

        String body = createStaff(admin, "New Manager", email, NEW_PASSWORD, "MANAGER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffCode").value(matchesPattern("STAFF-\\d{2,}")))
                .andExpect(jsonPath("$.name").value("New Manager"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.staffCode");
        // The password is never returned, and no hash either.
        assertThat(body).doesNotContain(NEW_PASSWORD).doesNotContain("passwordHash").doesNotContain("$2");

        // The new manager can sign in with it, and what they get is a manager's.
        mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.staffCode").value(code))
                .andExpect(jsonPath("$.staff.role").value("MANAGER"));
        // Stored only as a BCrypt hash.
        assertThat(jdbc.queryForObject("SELECT password_hash FROM support_staff WHERE staff_code = ?", String.class, code))
                .startsWith("$2").doesNotContain(NEW_PASSWORD);

        createStaff(admin, "New Agent", uniqueEmail(), NEW_PASSWORD, "TICKET_AGENT")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("TICKET_AGENT"));

        // Recorded: who created whom, as what.
        List<Map<String, Object>> trail = auditFor(code);
        assertThat(trail).hasSize(1);
        assertThat(trail.getFirst()).containsEntry("EVENT_TYPE", "STAFF_CREATED").containsEntry("ACTOR", admin.staffCode())
                .containsEntry("NEW_VALUE", "MANAGER");
        assertThat(trail.getFirst().get("PREVIOUS_VALUE")).isNull();
    }

    @Test
    void creationIsRefusedForAdminsUnknownRolesBadInputAndDuplicateEmails() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class);
        String email = uniqueEmail();

        createStaff(admin, "Another Admin", email, NEW_PASSWORD, "ADMIN").andExpect(status().isBadRequest());     // never an admin from here
        createStaff(admin, "Legacy Role", email, NEW_PASSWORD, "SUPPORT").andExpect(status().isBadRequest());      // the retired role
        createStaff(admin, "Nonsense", email, NEW_PASSWORD, "OVERLORD").andExpect(status().isBadRequest());
        createStaff(admin, "Short Password", email, "short", "MANAGER").andExpect(status().isBadRequest());
        createStaff(admin, "No Email", "not-an-email", NEW_PASSWORD, "MANAGER").andExpect(status().isBadRequest());
        createStaff(admin, " ", email, NEW_PASSWORD, "MANAGER").andExpect(status().isBadRequest());
        mvc.perform(as(admin, post(STAFF).contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isBadRequest());
        mvc.perform(as(admin, post(STAFF).contentType(MediaType.APPLICATION_JSON).content("not json"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class)).isEqualTo(before);

        createStaff(admin, "First", email, NEW_PASSWORD, "MANAGER").andExpect(status().isCreated());
        createStaff(admin, "Second", email.toUpperCase(), NEW_PASSWORD, "TICKET_AGENT").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class)).isEqualTo(before + 1);
    }

    @Test
    void thePasswordRequestIsNotPrintableByAccident() {
        var request = new com.handoffly.support.dto.CreateStaffRequest("A", "a@example.test", NEW_PASSWORD, SupportRole.MANAGER);
        assertThat(request.toString()).doesNotContain(NEW_PASSWORD).contains("***");
    }

    // ------------------------------------------------------------------ list and search

    @Test
    void theStaffListIsSearchableByIdNameEmailRoleAndStatusAndShowsNoCredentials() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        SupportStaff manager = staffService.provision("Zelda " + marker, "zelda-" + marker + "@support.example.test", STAFF_PASSWORD, SupportRole.MANAGER);
        SupportStaff agent = staffService.provision("Yuri " + marker, "yuri-" + marker + "@support.example.test", STAFF_PASSWORD, SupportRole.TICKET_AGENT);
        staffService.provision("Xena " + marker, "xena-" + marker + "@support.example.test", STAFF_PASSWORD, SupportRole.TICKET_AGENT);
        setActive(admin, agent.getStaffCode(), false, null).andExpect(status().isOk());

        String all = mvc.perform(as(admin, get(STAFF).param("q", marker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain("passwordHash", "password_hash", "$2a$", "token");

        mvc.perform(as(admin, get(STAFF).param("q", manager.getStaffCode()))).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Zelda " + marker));
        mvc.perform(as(admin, get(STAFF).param("q", "ZELDA " + marker))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get(STAFF).param("q", "yuri-" + marker + "@SUPPORT"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get(STAFF).param("q", marker).param("role", "TICKET_AGENT"))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(admin, get(STAFF).param("q", marker).param("role", "MANAGER"))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get(STAFF).param("q", marker).param("active", "false")))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].staffCode").value(agent.getStaffCode()))
                .andExpect(jsonPath("$.content[0].active").value(false));
        mvc.perform(as(admin, get(STAFF).param("q", marker).param("active", "true"))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(admin, get(STAFF).param("q", marker).param("role", "TICKET_AGENT").param("active", "true")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get(STAFF).param("q", "no-such-person-" + marker))).andExpect(jsonPath("$.totalElements").value(0));
        // Bad filter values are refused; a caller-chosen sort or huge page is ignored/capped.
        mvc.perform(as(admin, get(STAFF).param("role", "SUPPORT"))).andExpect(status().isBadRequest());
        mvc.perform(as(admin, get(STAFF).param("sort", "passwordHash,asc").param("size", "1000")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void theStaffListIsInStaffIdOrderWhateverSortIsAskedFor() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        String body = mvc.perform(as(admin, get(STAFF).param("sort", "name,desc"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(body, "$.content[*].staffCode");
        assertThat(codes).isSortedAccordingTo(String::compareTo);
    }

    // ------------------------------------------------------------------ change role

    @Test
    void changingARoleNeedsAnExplicitFromAndToIsAuditedAndAppliesAtOnce() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        SupportStaff target = provisioned(SupportRole.MANAGER);
        StaffAccount manager = loginStaff(target.getEmail());
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        mvc.perform(as(manager, get("/api/v1/support/customers/" + customer.accountCode()))).andExpect(status().isOk());

        changeRole(admin, target.getStaffCode(), "MANAGER", "TICKET_AGENT", "Moved to the ticket desk.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TICKET_AGENT"))
                .andExpect(jsonPath("$.staffCode").value(target.getStaffCode()));

        // Same token, new powers — no new sign-in needed: customer administration is gone, tickets stay.
        mvc.perform(as(manager, get("/api/v1/support/customers/" + customer.accountCode()))).andExpect(status().isForbidden());
        mvc.perform(as(manager, get("/api/v1/support/tickets"))).andExpect(status().isOk());
        mvc.perform(as(manager, get("/api/v1/support/auth/me"))).andExpect(jsonPath("$.role").value("TICKET_AGENT"));

        List<Map<String, Object>> trail = auditFor(target.getStaffCode());
        assertThat(trail).hasSize(1);
        assertThat(trail.getFirst()).containsEntry("EVENT_TYPE", "STAFF_ROLE_CHANGED").containsEntry("PREVIOUS_VALUE", "MANAGER")
                .containsEntry("NEW_VALUE", "TICKET_AGENT").containsEntry("REASON", "Moved to the ticket desk.")
                .containsEntry("ACTOR", admin.staffCode());

        // And back up again.
        changeRole(admin, target.getStaffCode(), "TICKET_AGENT", "MANAGER", null).andExpect(status().isOk());
        mvc.perform(as(manager, get("/api/v1/support/customers/" + customer.accountCode()))).andExpect(status().isOk());
        assertThat(auditFor(target.getStaffCode())).hasSize(2);
    }

    @Test
    void staleNoOpAndForbiddenRoleChangesAreRefusedAndLeaveNoTrace() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        SupportStaff target = provisioned(SupportRole.MANAGER);
        String code = target.getStaffCode();

        changeRole(admin, code, "TICKET_AGENT", "MANAGER", null).andExpect(status().isConflict());          // the admin's view was stale
        changeRole(admin, code, "MANAGER", "MANAGER", null).andExpect(status().isConflict());               // nothing to change
        changeRole(admin, code, "MANAGER", "ADMIN", null).andExpect(status().isBadRequest());               // admins are not made here
        changeRole(admin, code, "MANAGER", "SUPPORT", null).andExpect(status().isBadRequest());             // the retired role
        mvc.perform(as(admin, put(STAFF + "/" + code + "/role").contentType(MediaType.APPLICATION_JSON).content("{\"toRole\":\"TICKET_AGENT\"}")))
                .andExpect(status().isBadRequest());                                                        // must say what it is now
        changeRole(admin, "STAFF-999999", "MANAGER", "TICKET_AGENT", null).andExpect(status().isNotFound());
        changeRole(admin, admin.staffCode(), "ADMIN", "MANAGER", null).andExpect(status().isConflict());    // an admin cannot demote themselves...
        changeRole(admin, registerStaff(SupportRole.ADMIN).staffCode(), "ADMIN", "MANAGER", null).andExpect(status().isConflict());  // ...or another admin

        assertThat(jdbc.queryForObject("SELECT role FROM support_staff WHERE staff_code = ?", String.class, code)).isEqualTo("MANAGER");
        assertThat(auditFor(code)).isEmpty();
    }

    // ------------------------------------------------------------------ deactivate / reactivate

    @Test
    void deactivatingStopsLoginAndTokensAtOnceAndReactivatingRestoresThem() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        SupportStaff target = provisioned(SupportRole.TICKET_AGENT);
        StaffAccount agent = loginStaff(target.getEmail());
        mvc.perform(as(agent, get("/api/v1/support/dashboard"))).andExpect(status().isOk());

        setActive(admin, target.getStaffCode(), false, "Left the company.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // The token they already hold is dead, and they cannot sign in again.
        mvc.perform(as(agent, get("/api/v1/support/dashboard"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.getEmail() + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        // Never deleted: the record, its history and its Staff ID remain, and the list still shows them (as inactive).
        mvc.perform(as(admin, get(STAFF).param("q", target.getStaffCode())))
                .andExpect(jsonPath("$.content[0].active").value(false));

        setActive(admin, target.getStaffCode(), true, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mvc.perform(as(agent, get("/api/v1/support/dashboard"))).andExpect(status().isOk());   // the same token works again
        loginStaff(target.getEmail());

        List<Map<String, Object>> trail = auditFor(target.getStaffCode());
        assertThat(trail).extracting(row -> row.get("EVENT_TYPE")).containsExactly("STAFF_DEACTIVATED", "STAFF_REACTIVATED");
        assertThat(trail.getFirst()).containsEntry("PREVIOUS_VALUE", "ACTIVE").containsEntry("NEW_VALUE", "INACTIVE")
                .containsEntry("REASON", "Left the company.").containsEntry("ACTOR", admin.staffCode());
        assertThat(trail.get(1)).containsEntry("PREVIOUS_VALUE", "INACTIVE").containsEntry("NEW_VALUE", "ACTIVE");
    }

    @Test
    void pointlessOrForbiddenActivationChangesAreRefusedAndLeaveNoTrace() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        SupportStaff target = provisioned(SupportRole.MANAGER);

        setActive(admin, target.getStaffCode(), true, null).andExpect(status().isConflict());              // already active
        setActive(admin, "STAFF-999999", false, null).andExpect(status().isNotFound());
        setActive(admin, admin.staffCode(), false, null).andExpect(status().isConflict());                   // an admin cannot lock themselves out
        setActive(admin, registerStaff(SupportRole.ADMIN).staffCode(), false, null).andExpect(status().isConflict());
        mvc.perform(as(admin, put(STAFF + "/" + target.getStaffCode() + "/active").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest());

        assertThat(auditFor(target.getStaffCode())).isEmpty();
        mvc.perform(as(admin, get("/api/v1/support/auth/me"))).andExpect(status().isOk());   // the admin is still in
    }

    // ------------------------------------------------------------------ who may do any of it, and the audit trail

    @Test
    void managersAndTicketAgentsCannotManageStaffAndNothingChanges() throws Exception {
        SupportStaff victim = provisioned(SupportRole.MANAGER);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class);
        for (SupportRole role : List.of(SupportRole.MANAGER, SupportRole.TICKET_AGENT)) {
            StaffAccount staff = registerStaff(role);
            before++;
            mvc.perform(as(staff, get(STAFF))).andExpect(status().isForbidden());
            createStaff(staff, "Sneaky", uniqueEmail(), NEW_PASSWORD, "MANAGER").andExpect(status().isForbidden());
            changeRole(staff, victim.getStaffCode(), "MANAGER", "TICKET_AGENT", null).andExpect(status().isForbidden());
            changeRole(staff, staff.staffCode(), role.name(), "MANAGER".equals(role.name()) ? "TICKET_AGENT" : "MANAGER", null)
                    .andExpect(status().isForbidden());   // not even their own
            setActive(staff, victim.getStaffCode(), false, null).andExpect(status().isForbidden());
            mvc.perform(as(staff, get("/api/v1/support/audit"))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT role || ':' || active FROM support_staff WHERE staff_code = ?", String.class, victim.getStaffCode()))
                .isEqualTo("MANAGER:TRUE");
        assertThat(auditFor(victim.getStaffCode())).isEmpty();
    }

    @Test
    void theAuditTrailShowsStaffAndCustomerChangesNewestFirstToAdminsOnly() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        SupportStaff target = provisioned(SupportRole.MANAGER);
        Account customer = register();
        changeRole(admin, target.getStaffCode(), "MANAGER", "TICKET_AGENT", "Desk move").andExpect(status().isOk());
        mvc.perform(as(admin, put("/api/v1/support/customers/" + customer.accountCode() + "/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"QUARTERLY\"}"))).andExpect(status().isOk());

        mvc.perform(as(admin, get("/api/v1/support/audit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.content[0].subjectCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.content[0].staffCode").value(admin.staffCode()))
                .andExpect(jsonPath("$.content[1].type").value("STAFF_ROLE_CHANGED"))
                .andExpect(jsonPath("$.content[1].subjectCode").value(target.getStaffCode()))
                .andExpect(jsonPath("$.content[1].previousValue").value("MANAGER"))
                .andExpect(jsonPath("$.content[1].newValue").value("TICKET_AGENT"))
                .andExpect(jsonPath("$.content[1].reason").value("Desk move"))
                .andExpect(jsonPath("$.content[*].type", hasItem("STAFF_CREATED")))
                .andExpect(jsonPath("$.content[*].type", not(hasItem("NOT_A_TYPE"))));
        // A manager sees the plan history on the customer's own profile (part of the profile), not the whole trail.
        mvc.perform(as(registerStaff(SupportRole.MANAGER), get("/api/v1/support/customers/" + customer.accountCode())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recentChanges[0].type").value("PLAN_CHANGED"));
    }

    @Test
    void aStaffMemberCreatedByAnAdminIsInTheirOwnCapacityNotTheAdminsAndCanNeverBecomeOne() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        String email = uniqueEmail();
        createStaff(admin, "Ticket Newbie", email, NEW_PASSWORD, "TICKET_AGENT").andExpect(status().isCreated());
        String token = JsonPath.read(mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}")).andReturn().getResponse().getContentAsString(), "$.token");
        Bearer newbie = () -> token;

        mvc.perform(as(newbie, get("/api/v1/support/tickets"))).andExpect(status().isOk());
        mvc.perform(as(newbie, get(STAFF))).andExpect(status().isForbidden());
        mvc.perform(as(newbie, get("/api/v1/support/customers"))).andExpect(status().isForbidden());
    }

    @Test
    void aCompleteStaffIdFindsExactlyThatPersonWhileAPartialOneStillSearches() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        for (int i = 0; i < 12; i++) {
            registerStaff(SupportRole.TICKET_AGENT);
        }
        mvc.perform(as(admin, get(STAFF).param("q", admin.staffCode()))).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].staffCode").value(admin.staffCode()));
        mvc.perform(as(admin, get(STAFF).param("q", admin.staffCode().toLowerCase()))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(as(admin, get(STAFF).param("q", "STAFF-999999"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(admin, get(STAFF).param("q", "STAFF-"))).andExpect(jsonPath("$.totalElements", org.hamcrest.Matchers.greaterThan(10)));
    }
}
