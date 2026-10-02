package com.handoffly.support.staff;

import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A member of the support team. This is a separate identity from a customer: its own table, its own
 * login and token, its own roles. A staff member is created only by controlled provisioning and has no
 * handoffs, plan or customer data of their own. There are deliberately no setters: a record changes only
 * through the three explicit operations below (always done by an administrator, always audited).
 */
@Entity
@Table(name = "support_staff")
public class SupportStaff extends BaseEntity {

    @Column(name = "staff_code", nullable = false, unique = true, updatable = false, length = 20)
    private String staffCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportRole role;

    /** An inactive staff member cannot sign in, and a token they already hold stops working at once. */
    @Column(nullable = false)
    private boolean active = true;

    protected SupportStaff() {
        // JPA
    }

    public SupportStaff(String staffCode, String name, String email, String passwordHash, SupportRole role) {
        this.staffCode = staffCode;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public void changeRole(SupportRole newRole) { this.role = newRole; }

    public void deactivate() { this.active = false; }

    public void reactivate() { this.active = true; }

    public String getStaffCode() { return staffCode; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public SupportRole getRole() { return role; }
    public boolean isActive() { return active; }
}
