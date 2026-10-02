package com.handoffly.auth;

import com.handoffly.auth.dto.AuthResponse;
import com.handoffly.auth.dto.LoginRequest;
import com.handoffly.auth.dto.RegisterRequest;
import com.handoffly.auth.dto.UserResponse;
import com.handoffly.auth.jwt.JwtService;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.UnauthorizedException;
import com.handoffly.common.sequence.SequenceService;
import com.handoffly.common.util.PublicCode;
import com.handoffly.user.User;
import com.handoffly.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account registration and authentication. Passwords are BCrypt-hashed; login
 * returns a stateless JWT. Login failures are deliberately generic to avoid
 * revealing whether an email exists. This is the CUSTOMER login only; support staff have their
 * own identity and login (see the support module).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SequenceService sequenceService;
    private final String supportPhone;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       SequenceService sequenceService, HandOfflyProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sequenceService = sequenceService;
        this.supportPhone = properties.getSupport().getPhone();
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists.");
        }
        // Public registration only ever creates a customer, on the default plan. Support staff are a
        // separate identity and can never be created here.
        User user = new User(
                PublicCode.account(sequenceService.next(SequenceService.ACCOUNT)),
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim(),
                blankToNull(request.organization()),
                blankToNull(request.phone()));
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .orElse(null);
        if (user == null || !user.isEnabled()
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password.");
        }
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return userRepository.findById(userId)
                .map(user -> UserResponse.from(user, supportPhone))
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists."));
    }

    private AuthResponse toAuthResponse(User user) {
        JwtService.IssuedToken issued = jwtService.issueForCustomer(user);
        return new AuthResponse(issued.token(), issued.expiresAt(), UserResponse.from(user, supportPhone));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
