package com.handoffly.support.staff;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** How support staff come into being: only by controlled provisioning, never by public registration. */
class SupportStaffProvisioningTest extends ApiTestBase {

    private static String uniqueEmail() {
        return "provisioned-" + UUID.randomUUID().toString().substring(0, 10) + "@support.example.test";
    }

    private SupportStaffProvisioner provisionerFor(String name, String email, String password, String role) {
        HandOfflyProperties properties = new HandOfflyProperties();
        HandOfflyProperties.Provision provision = properties.getSupport().getProvision();
        provision.setName(name);
        provision.setEmail(email);
        provision.setPassword(password);
        provision.setRole(role);
        return new SupportStaffProvisioner(staffService, properties);
    }

    private long staffCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class);
    }

    @Test
    void provisioningCreatesAnActiveStaffMemberWithAStaffIdAndAHashedPassword() {
        String email = uniqueEmail();
        SupportStaff staff = staffService.provision("  Priya Support  ", "  " + email + "  ", "a-long-enough-pass", SupportRole.MANAGER);

        assertThat(staff.getStaffCode()).matches("STAFF-\\d{2,}");
        assertThat(staff.getName()).isEqualTo("Priya Support");
        assertThat(staff.getEmail()).isEqualTo(email);
        assertThat(staff.getRole()).isEqualTo(SupportRole.MANAGER);
        assertThat(staff.isActive()).isTrue();
        assertThat(staff.getPasswordHash()).startsWith("$2").doesNotContain("a-long-enough-pass");
    }

    @Test
    void staffIdsCountUpAndAreNeverReused() {
        SupportStaff first = staffService.provision("One", uniqueEmail(), "a-long-enough-pass", SupportRole.MANAGER);
        SupportStaff second = staffService.provision("Two", uniqueEmail(), "a-long-enough-pass", SupportRole.ADMIN);
        assertThat(Long.parseLong(second.getStaffCode().substring("STAFF-".length())))
                .isEqualTo(Long.parseLong(first.getStaffCode().substring("STAFF-".length())) + 1);
    }

    @Test
    void unacceptableStaffDetailsAreRefusedAndNothingIsCreated() {
        long before = staffCount();
        String email = uniqueEmail();

        assertThatThrownBy(() -> staffService.provision("Name", email, "short-pass", SupportRole.MANAGER))
                .isInstanceOf(BadRequestException.class);                       // under 12 characters
        assertThatThrownBy(() -> staffService.provision("Name", email, "x".repeat(73), SupportRole.MANAGER))
                .isInstanceOf(BadRequestException.class);                       // beyond what BCrypt can use
        assertThatThrownBy(() -> staffService.provision("Name", email, null, SupportRole.MANAGER))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> staffService.provision("  ", email, "a-long-enough-pass", SupportRole.MANAGER))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> staffService.provision("Name", "not-an-email", "a-long-enough-pass", SupportRole.MANAGER))
                .isInstanceOf(BadRequestException.class);
        assertThat(staffCount()).isEqualTo(before);
    }

    @Test
    void aStaffEmailCanOnlyBeUsedOnceIgnoringCase() {
        String email = uniqueEmail();
        staffService.provision("First", email, "a-long-enough-pass", SupportRole.MANAGER);
        assertThatThrownBy(() -> staffService.provision("Second", email.toUpperCase(), "another-long-pass", SupportRole.ADMIN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theStartupProvisionerCreatesTheConfiguredStaffMemberOnce() throws Exception {
        String email = uniqueEmail();
        long before = staffCount();

        provisionerFor("Bootstrap Admin", email, "bootstrap-pass-123", "admin").run(null);   // role is case-insensitive
        assertThat(staffCount()).isEqualTo(before + 1);
        StaffAccount admin = loginWith(email, "bootstrap-pass-123");
        assertThat(admin.staffCode()).matches("STAFF-\\d{2,}");
        mvc.perform(as(admin, get("/api/v1/support/auth/me")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT role FROM support_staff WHERE email = ?", String.class, email)).isEqualTo("ADMIN");

        // Run again with a DIFFERENT password: nothing changes, and the existing password is never overwritten.
        provisionerFor("Bootstrap Admin", email, "a-different-password-456", "ADMIN").run(null);
        assertThat(staffCount()).isEqualTo(before + 1);
        loginWith(email, "bootstrap-pass-123");
        mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"a-different-password-456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withNothingConfiguredNothingIsCreatedAndABadSettingNeverStopsStartup() throws Exception {
        long before = staffCount();

        provisionerFor("", "", "", "ADMIN").run(null);                                          // nothing configured
        provisionerFor("Name", uniqueEmail(), "short", "ADMIN").run(null);                        // password too short
        provisionerFor("Name", uniqueEmail(), "a-long-enough-pass", "SUPERUSER").run(null);       // not a staff role
        provisionerFor("Name", uniqueEmail(), "a-long-enough-pass", "SUPPORT").run(null);         // the retired role: no legacy path
        provisionerFor("Name", "", "a-long-enough-pass", "ADMIN").run(null);                      // no email

        assertThat(staffCount()).isEqualTo(before);   // none created, and none of those calls threw
    }

    @Test
    void publicRegistrationCannotCreateStaffInAnyWay() throws Exception {
        long before = staffCount();
        String email = uniqueEmail();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Wannabe\","
                                + "\"role\":\"SUPPORT\",\"staff\":true,\"staffCode\":\"STAFF-000001\"}"))
                .andExpect(status().isCreated());
        assertThat(staffCount()).isEqualTo(before);
        // And there is no public endpoint that creates staff.
        for (String path : new String[]{"/api/v1/support/auth/register", "/api/v1/support/staff", "/api/v1/support/auth/signup"}) {
            int result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"" + STAFF_PASSWORD + "\",\"name\":\"X\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(result).as(path).isIn(401, 404, 405);
        }
        assertThat(staffCount()).isEqualTo(before);
    }

    private StaffAccount loginWith(String email, String password) throws Exception {
        String json = mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new StaffAccount(
                jdbc.queryForObject("SELECT id FROM support_staff WHERE email = ?", Long.class, email),
                JsonPath.read(json, "$.staff.staffCode"), email,
                JsonPath.read(json, "$.token"));
    }
}
