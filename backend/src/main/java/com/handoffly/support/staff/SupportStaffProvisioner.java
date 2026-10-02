package com.handoffly.support.staff;

import com.handoffly.common.config.HandOfflyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * The BOOTSTRAP way a support staff account comes into being — chiefly the first ADMIN, who then creates
 * everyone else through staff management. When an email and password are supplied
 * through the environment (see {@code SUPPORT_STAFF_*} in the README), this creates that staff member on
 * startup — unless one with that email already exists, in which case nothing changes and the existing
 * password is never overwritten. Nothing is built in: with no settings, nothing is created.
 */
@Component
public class SupportStaffProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SupportStaffProvisioner.class);

    private final SupportStaffService staffService;
    private final HandOfflyProperties.Provision settings;

    public SupportStaffProvisioner(SupportStaffService staffService, HandOfflyProperties properties) {
        this.staffService = staffService;
        this.settings = properties.getSupport().getProvision();
    }

    private static SupportRole parseRole(String value) {
        try {
            return SupportRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SUPPORT_STAFF_ROLE must be one of " + Arrays.toString(SupportRole.values())
                    + " (the old SUPPORT role no longer exists).");
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (settings.getEmail().isBlank() && settings.getPassword().isBlank()) {
            return;
        }
        try {
            if (staffService.exists(settings.getEmail())) {
                log.info("Support staff {} already exists; nothing was changed (an existing password is never overwritten).",
                        settings.getEmail());
                return;
            }
            SupportRole role = parseRole(settings.getRole());
            SupportStaff created = staffService.provision(
                    settings.getName(), settings.getEmail(), settings.getPassword(), role);
            log.info("Support staff {} ({}) was created for {}. Remove the SUPPORT_STAFF_PASSWORD setting now.",
                    created.getStaffCode(), created.getRole(), created.getEmail());
        } catch (RuntimeException e) {
            // A bad provisioning setting must not stop the customer application from starting; the reason is logged
            // (never the password).
            log.error("Support staff was NOT created: {}", e.getMessage());
        }
    }
}
