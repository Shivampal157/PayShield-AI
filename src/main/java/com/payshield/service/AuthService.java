package com.payshield.service;

import com.payshield.config.AppProperties;
import com.payshield.domain.*;
import com.payshield.dto.AuthDtos.*;
import com.payshield.exception.ApiException;
import com.payshield.repository.PasswordResetTokenRepository;
import com.payshield.repository.RefreshTokenRepository;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.JwtTokenService;
import com.payshield.util.HashUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final BigDecimal INITIAL_WALLET_BALANCE = new BigDecimal("5000.0000");

    private final UserAccountRepository userRepository;
    private final WalletRepository walletRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final SecurityAuditService auditService;
    private final LocalDevEmailAdapter emailAdapter;
    private final AppProperties properties;

    // Sliding window login attempt tracker keyed by IP or email
    private final Map<String, Deque<Long>> loginRateWindows = new ConcurrentHashMap<>();

    public AuthService(UserAccountRepository userRepository,
                       WalletRepository walletRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       SecurityAuditService auditService,
                       LocalDevEmailAdapter emailAdapter,
                       AppProperties properties) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.auditService = auditService;
        this.emailAdapter = emailAdapter;
        this.properties = properties;
    }

    @Transactional
    public AuthSessionResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            auditService.recordEvent(null, normalizedEmail, "REGISTER_CONFLICT", "USER_ACCOUNT", null, ipAddress,
                    "Registration attempted with existing email");
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "An account with this email address already exists.");
        }

        String currency = (request.initialCurrency() == null || request.initialCurrency().isBlank())
                ? "USD"
                : request.initialCurrency().trim().toUpperCase();
        if (!SupportedCurrency.isValid(currency)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Unsupported currency: " + currency + ". Supported: USD, EUR, GBP.");
        }

        UserAccount user = new UserAccount(
                UUID.randomUUID(),
                normalizedEmail,
                request.fullName().trim(),
                passwordEncoder.encode(request.password()),
                Role.CUSTOMER
        );
        userRepository.save(user);

        String accountNumber = generateAccountNumber(currency, user.getId());
        Wallet wallet = new Wallet(UUID.randomUUID(), user.getId(), accountNumber, currency, INITIAL_WALLET_BALANCE);
        walletRepository.save(wallet);

        auditService.recordEvent(user.getId(), user.getEmail(), "USER_REGISTERED", "USER_ACCOUNT",
                user.getId().toString(), ipAddress, "Customer account and starter " + currency + " wallet provisioned");

        return issueSession(user, UUID.randomUUID(), ipAddress, userAgent);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthSessionResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String normalizedEmail = request.email().trim().toLowerCase();
        enforceLoginRateLimit(ipAddress != null ? ipAddress : normalizedEmail);

        UserAccount user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null) {
            auditService.recordEvent(null, normalizedEmail, "LOGIN_FAILED", "USER_ACCOUNT", null, ipAddress,
                    "Login attempt for unknown email");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }

        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            auditService.recordEvent(user.getId(), user.getEmail(), "LOGIN_LOCKED", "USER_ACCOUNT",
                    user.getId().toString(), ipAddress, "Login blocked due to active account lockout");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "Account is temporarily locked due to repeated failed sign-in attempts. Please try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= properties.getSecurity().getAccountLockoutThreshold()) {
                user.setLockedUntil(now.plusSeconds(properties.getSecurity().getAccountLockoutMinutes() * 60L));
            }
            user.setUpdatedAt(now);
            userRepository.save(user);

            auditService.recordEvent(user.getId(), user.getEmail(), "LOGIN_FAILED", "USER_ACCOUNT",
                    user.getId().toString(), ipAddress, "Invalid password attempt #" + attempts);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);
        userRepository.save(user);

        auditService.recordEvent(user.getId(), user.getEmail(), "LOGIN_SUCCESS", "USER_ACCOUNT",
                user.getId().toString(), ipAddress, "User authenticated successfully");

        return issueSession(user, UUID.randomUUID(), ipAddress, userAgent);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthSessionResponse refreshSession(String rawRefreshToken, String ipAddress, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_MISSING", "Refresh token is required.");
        }

        String tokenHash = HashUtils.sha256Hex(rawRefreshToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                        "Invalid or unrecognized refresh token."));

        if (existing.isRevoked()) {
            // Token reuse detection: revoke the entire token family immediately
            refreshTokenRepository.revokeByFamilyId(existing.getFamilyId());
            auditService.recordEvent(existing.getUserId(), "session", "REFRESH_TOKEN_REUSE_DETECTED",
                    "REFRESH_TOKEN_FAMILY", existing.getFamilyId().toString(), ipAddress,
                    "Revoked refresh token replayed; entire token family invalidated");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "Refresh token has already been used or revoked. Session family terminated.");
        }

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            existing.setRevoked(true);
            refreshTokenRepository.save(existing);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED",
                    "Refresh token has expired. Please sign in again.");
        }

        UserAccount user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "Account not found."));

        String newRawRefreshToken = jwtTokenService.generateSecureOpaqueToken();
        String newTokenHash = HashUtils.sha256Hex(newRawRefreshToken);

        existing.setRevoked(true);
        existing.setReplacedByTokenHash(newTokenHash);
        refreshTokenRepository.save(existing);

        Instant newExpiry = Instant.now().plusSeconds(properties.getSecurity().getRefreshTokenTtlSeconds());
        RefreshToken rotated = new RefreshToken(
                UUID.randomUUID(),
                newTokenHash,
                user.getId(),
                existing.getFamilyId(),
                newExpiry,
                ipAddress,
                userAgent
        );
        refreshTokenRepository.save(rotated);

        String accessToken = jwtTokenService.generateAccessToken(user);
        String csrfToken = jwtTokenService.generateSecureOpaqueToken();

        auditService.recordEvent(user.getId(), user.getEmail(), "TOKEN_REFRESHED", "REFRESH_TOKEN",
                rotated.getId().toString(), ipAddress, "Refresh token rotated cleanly");

        return new AuthSessionResponse(
                toProfileResponse(user),
                accessToken,
                newRawRefreshToken,
                csrfToken,
                properties.getSecurity().getAccessTokenTtlSeconds()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken, UUID userId, String actorEmail, String ipAddress) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = HashUtils.sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                refreshTokenRepository.revokeByFamilyId(token.getFamilyId());
            });
        } else if (userId != null) {
            refreshTokenRepository.revokeAllByUserId(userId);
        }
        auditService.recordEvent(userId, actorEmail != null ? actorEmail : "session",
                "USER_LOGOUT", "USER_ACCOUNT", userId != null ? userId.toString() : null,
                ipAddress, "Session logged out and refresh tokens revoked");
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequest request, String ipAddress) {
        String normalizedEmail = request.email().trim().toLowerCase();
        userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            String rawToken = jwtTokenService.generateSecureOpaqueToken();
            String tokenHash = HashUtils.sha256Hex(rawToken);
            Instant expiresAt = Instant.now().plusSeconds(900); // 15 minutes
            PasswordResetToken resetToken = new PasswordResetToken(
                    UUID.randomUUID(),
                    tokenHash,
                    user.getId(),
                    expiresAt
            );
            passwordResetTokenRepository.save(resetToken);
            emailAdapter.sendPasswordResetEmail(user.getEmail(), rawToken, expiresAt);
            auditService.recordEvent(user.getId(), user.getEmail(), "PASSWORD_RESET_REQUESTED",
                    "USER_ACCOUNT", user.getId().toString(), ipAddress,
                    "Single-use 15-minute password reset token generated");
        });
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request, String ipAddress) {
        String tokenHash = HashUtils.sha256Hex(request.resetToken().trim());
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID",
                        "Invalid or expired password reset token."));

        if (resetToken.isUsed() || resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_EXPIRED",
                    "Password reset token has already been used or expired.");
        }

        UserAccount user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found."));

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(user.getId());

        auditService.recordEvent(user.getId(), user.getEmail(), "PASSWORD_RESET_COMPLETED",
                "USER_ACCOUNT", user.getId().toString(), ipAddress,
                "Password updated via single-use token and all prior sessions revoked");
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentProfile(UUID userId) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User account not found."));
        return toProfileResponse(user);
    }

    private AuthSessionResponse issueSession(UserAccount user, UUID familyId, String ipAddress, String userAgent) {
        String accessToken = jwtTokenService.generateAccessToken(user);
        String rawRefreshToken = jwtTokenService.generateSecureOpaqueToken();
        String refreshTokenHash = HashUtils.sha256Hex(rawRefreshToken);
        String csrfToken = jwtTokenService.generateSecureOpaqueToken();

        Instant refreshExpiry = Instant.now().plusSeconds(properties.getSecurity().getRefreshTokenTtlSeconds());
        RefreshToken refreshToken = new RefreshToken(
                UUID.randomUUID(),
                refreshTokenHash,
                user.getId(),
                familyId,
                refreshExpiry,
                ipAddress,
                userAgent
        );
        refreshTokenRepository.save(refreshToken);

        return new AuthSessionResponse(
                toProfileResponse(user),
                accessToken,
                rawRefreshToken,
                csrfToken,
                properties.getSecurity().getAccessTokenTtlSeconds()
        );
    }

    private UserProfileResponse toProfileResponse(UserAccount user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getCreatedAt(),
                properties.getEnvironmentLabel()
        );
    }

    private void enforceLoginRateLimit(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;
        int maxAttempts = properties.getSecurity().getMaxLoginAttemptsPerMinute();

        Deque<Long> timestamps = loginRateWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxAttempts) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMIT_EXCEEDED",
                        "Too many authentication requests from this client. Please wait 60 seconds.");
            }
            timestamps.addLast(now);
        }
    }

    public void resetRateLimiterForTest() {
        loginRateWindows.clear();
    }

    public static String generateAccountNumber(String currency, UUID userId) {
        String shortHex = userId.toString().replace("-", "").substring(0, 10).toUpperCase();
        return "PS-" + currency.toUpperCase() + "-" + shortHex;
    }
}
