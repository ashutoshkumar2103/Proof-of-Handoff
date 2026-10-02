package com.handoffly.auth;

import com.handoffly.auth.dto.AuthResponse;
import com.handoffly.auth.dto.ChangePasswordRequest;
import com.handoffly.auth.dto.LoginRequest;
import com.handoffly.auth.dto.RegisterRequest;
import com.handoffly.auth.dto.UpdateProfileRequest;
import com.handoffly.auth.dto.UserResponse;
import com.handoffly.auth.jwt.JwtService;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.RateLimitedException;
import com.handoffly.common.error.UnauthorizedException;
import com.handoffly.common.sequence.SequenceService;
import com.handoffly.common.util.PublicCode;
import com.handoffly.common.web.RateLimiter;
import com.handoffly.user.User;
import com.handoffly.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

/**
 * Account registration and authentication. Passwords are BCrypt-hashed; login
 * returns a stateless JWT. Login failures are deliberately generic to avoid
 * revealing whether an email exists. This is the CUSTOMER login only; support staff have their
 * own identity and login (see the support module).
 */
@Service
public class AuthService {

    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    /** A real hash to compare against when the email is unknown, so that case takes as long as a wrong password. */
    private final String unknownAccountHash;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SequenceService sequenceService;
    private final String supportPhone;
    private final RateLimiter limiter;
    private final HandOfflyProperties.RateLimit limits;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       SequenceService sequenceService, HandOfflyProperties properties, RateLimiter limiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sequenceService = sequenceService;
        this.supportPhone = properties.getSupport().getPhone();
        this.limiter = limiter;
        this.limits = properties.getRateLimit();
        this.unknownAccountHash = passwordEncoder.encode("no-account-has-this-password");
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

    /**
     * Signs a customer in. Failed attempts for one email from one address are counted and, past a limit, refused
     * for a while - whether or not the account exists, so the limit tells nothing either. A success clears the count.
     * @param callerAddress where the request came from, for that count
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String callerAddress) {
        String email = request.email().trim();
        String failureKey = "login-failures:" + callerAddress + ":" + email.toLowerCase(Locale.ROOT);
        if (limiter.isLimited(failureKey, limits.getLoginFailuresPerAccount(), FAILURE_WINDOW)) {
            throw new RateLimitedException(FAILURE_WINDOW.toSeconds());
        }
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        // Always do one password comparison, so the time taken does not show whether the account exists.
        boolean passwordOk = passwordEncoder.matches(request.password(),
                user == null ? unknownAccountHash : user.getPasswordHash());
        if (user == null || !user.isEnabled() || !passwordOk) {
            limiter.hit(failureKey, limits.getLoginFailuresPerAccount(), FAILURE_WINDOW);
            throw new UnauthorizedException("Invalid email or password.");
        }
        limiter.reset(failureKey);
        return toAuthResponse(user);
    }

    /**
     * Changes a signed-in customer's password: the current one must be right, the new one must meet the policy
     * and differ from it. Every other session is signed out; the caller gets a fresh token so this one carries on.
     * A wrong current password is a 400 (not a 401, which the apps treat as "your session ended"), and repeated
     * wrong guesses are limited.
     */
    @Transactional
    public AuthResponse changePassword(Long userId, ChangePasswordRequest request) {
        String failureKey = "password-change-failures:" + userId;
        if (limiter.isLimited(failureKey, limits.getPasswordChangeFailures(), FAILURE_WINDOW)) {
            throw new RateLimitedException(FAILURE_WINDOW.toSeconds());
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            limiter.hit(failureKey, limits.getPasswordChangeFailures(), FAILURE_WINDOW);
            throw new BadRequestException("The current password is not correct.");
        }
        PasswordPolicy.require(request.newPassword(), request.confirmPassword());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Choose a new password that is different from the current one.");
        }
        limiter.reset(failureKey);
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        return toAuthResponse(user);
    }

    /** The customer edits their own name, organization and phone - nothing else about the account. */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists."));
        user.setDisplayName(request.displayName().trim());
        user.setOrganization(blankToNull(request.organization()));
        user.setPhone(blankToNull(request.phone()));
        return UserResponse.from(user, supportPhone);
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
