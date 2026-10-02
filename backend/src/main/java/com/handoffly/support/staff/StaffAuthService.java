package com.handoffly.support.staff;

import com.handoffly.auth.dto.LoginRequest;
import com.handoffly.auth.jwt.JwtService;
import com.handoffly.common.error.UnauthorizedException;
import com.handoffly.support.dto.StaffAuthResponse;
import com.handoffly.support.dto.StaffResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support staff sign-in, against {@code support_staff} only. A customer's email and password are not
 * staff credentials, and a staff member's are not customer credentials. Failures are deliberately
 * generic and take the same time whether or not the email belongs to a staff member.
 */
@Service
public class StaffAuthService {

    private final SupportStaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Compared against when the email is unknown, so an unknown email costs as much as a wrong password. */
    private final String decoyHash;

    public StaffAuthService(SupportStaffRepository staffRepository, PasswordEncoder passwordEncoder,
                            JwtService jwtService) {
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.decoyHash = passwordEncoder.encode("not-a-real-staff-password");
    }

    @Transactional(readOnly = true)
    public StaffAuthResponse login(LoginRequest request) {
        SupportStaff staff = staffRepository.findByEmailIgnoreCase(request.email().trim()).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(
                request.password(), staff != null ? staff.getPasswordHash() : decoyHash);
        if (staff == null || !staff.isActive() || !passwordMatches) {
            throw new UnauthorizedException("Invalid email or password.");
        }
        JwtService.IssuedToken issued = jwtService.issueForStaff(staff.getId(), staff.getEmail());
        return new StaffAuthResponse(issued.token(), issued.expiresAt(), StaffResponse.from(staff));
    }
}
