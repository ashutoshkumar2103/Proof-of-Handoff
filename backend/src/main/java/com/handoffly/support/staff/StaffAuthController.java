package com.handoffly.support.staff;

import com.handoffly.auth.dto.LoginRequest;
import com.handoffly.support.dto.StaffAuthResponse;
import com.handoffly.support.dto.StaffResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in for support staff. Deliberately separate from the customer {@code /auth} endpoints. */
@RestController
@RequestMapping("/api/v1/support/auth")
public class StaffAuthController {

    private final StaffAuthService authService;
    private final SupportStaffService staffService;

    public StaffAuthController(StaffAuthService authService, SupportStaffService staffService) {
        this.authService = authService;
        this.staffService = staffService;
    }

    @PostMapping("/login")
    public StaffAuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public StaffResponse me(@AuthenticationPrincipal StaffPrincipal principal) {
        return StaffResponse.from(staffService.getById(principal.id()));
    }
}
