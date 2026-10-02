package com.handoffly.support.dto;

import com.handoffly.support.staff.SupportRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An administrator creating a staff member. The password is only ever read here, hashed immediately and never
 * returned or logged (hence the {@code toString} that leaves it out).
 */
public record CreateStaffRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password,
        @NotNull SupportRole role
) {
    @Override
    public String toString() {
        return "CreateStaffRequest[name=" + name + ", email=" + email + ", role=" + role + ", password=***]";
    }
}
