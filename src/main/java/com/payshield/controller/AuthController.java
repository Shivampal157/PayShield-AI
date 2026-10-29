package com.payshield.controller;

import com.payshield.config.AppProperties;
import com.payshield.dto.AuthDtos.*;
import com.payshield.exception.ApiException;
import com.payshield.security.AuthenticatedUser;
import com.payshield.security.JwtAuthenticationFilter;
import com.payshield.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Authentication & Account Security", description = "Registration, login, token rotation, logout, password reset and profile")
public class AuthController {

    private final AuthService authService;
    private final AppProperties properties;

    public AuthController(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new customer account and provision starter wallet")
    public ResponseEntity<AuthSessionResponse> register(@Valid @RequestBody RegisterRequest request,
                                                        HttpServletRequest httpRequest,
                                                        HttpServletResponse httpResponse) {
        AuthSessionResponse session = authService.register(
                request,
                resolveClientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        attachAuthCookies(httpResponse, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and issue short-lived access JWT + rotating refresh token")
    public ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        AuthSessionResponse session = authService.login(
                request,
                resolveClientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        attachAuthCookies(httpResponse, session);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token and issue a fresh short-lived access token")
    public ResponseEntity<AuthSessionResponse> refresh(@RequestBody(required = false) RefreshTokenRequest body,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        String rawRefreshToken = (body != null && body.refreshToken() != null && !body.refreshToken().isBlank())
                ? body.refreshToken()
                : JwtAuthenticationFilter.extractCookie(httpRequest, JwtAuthenticationFilter.REFRESH_COOKIE_NAME);

        AuthSessionResponse session = authService.refreshSession(
                rawRefreshToken,
                resolveClientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        attachAuthCookies(httpResponse, session);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke refresh token family and clear authentication cookies")
    public ResponseEntity<Map<String, String>> logout(@RequestBody(required = false) RefreshTokenRequest body,
                                                      @AuthenticationPrincipal AuthenticatedUser user,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        String rawRefreshToken = (body != null && body.refreshToken() != null && !body.refreshToken().isBlank())
                ? body.refreshToken()
                : JwtAuthenticationFilter.extractCookie(httpRequest, JwtAuthenticationFilter.REFRESH_COOKIE_NAME);

        authService.logout(
                rawRefreshToken,
                user != null ? user.userId() : null,
                user != null ? user.email() : null,
                resolveClientIp(httpRequest)
        );
        clearAuthCookies(httpResponse);
        return ResponseEntity.ok(Map.of("status", "LOGGED_OUT", "message", "Session terminated and refresh tokens revoked."));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user profile and active role")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Not authenticated.");
        }
        return ResponseEntity.ok(authService.getCurrentProfile(user.userId()));
    }

    @PostMapping("/password-reset/request")
    @Operation(summary = "Request a short-lived, single-use password reset token")
    public ResponseEntity<Map<String, String>> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                                                    HttpServletRequest httpRequest) {
        authService.requestPasswordReset(request, resolveClientIp(httpRequest));
        return ResponseEntity.ok(Map.of(
                "status", "ACCEPTED",
                "message", "If an account exists for that email, password reset instructions have been dispatched."
        ));
    }

    @PostMapping("/password-reset/confirm")
    @Operation(summary = "Reset account password using a valid single-use reset token")
    public ResponseEntity<Map<String, String>> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                                                    HttpServletRequest httpRequest,
                                                                    HttpServletResponse httpResponse) {
        authService.confirmPasswordReset(request, resolveClientIp(httpRequest));
        clearAuthCookies(httpResponse);
        return ResponseEntity.ok(Map.of(
                "status", "PASSWORD_UPDATED",
                "message", "Password has been reset and all existing sessions have been revoked."
        ));
    }

    private void attachAuthCookies(HttpServletResponse response, AuthSessionResponse session) {
        boolean secure = properties.getSecurity().isCookieSecure();
        ResponseCookie accessCookie = ResponseCookie.from(JwtAuthenticationFilter.ACCESS_COOKIE_NAME, session.accessToken())
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.getSecurity().getAccessTokenTtlSeconds()))
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from(JwtAuthenticationFilter.REFRESH_COOKIE_NAME, session.refreshToken())
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(properties.getSecurity().getRefreshTokenTtlSeconds()))
                .build();

        ResponseCookie csrfCookie = ResponseCookie.from(JwtAuthenticationFilter.CSRF_COOKIE_NAME, session.csrfToken())
                .httpOnly(false) // Readable by same-origin JS to set X-CSRF-Token header
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.getSecurity().getRefreshTokenTtlSeconds()))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    private void clearAuthCookies(HttpServletResponse response) {
        boolean secure = properties.getSecurity().isCookieSecure();
        ResponseCookie accessCookie = ResponseCookie.from(JwtAuthenticationFilter.ACCESS_COOKIE_NAME, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(0).build();
        ResponseCookie refreshCookie = ResponseCookie.from(JwtAuthenticationFilter.REFRESH_COOKIE_NAME, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth").maxAge(0).build();
        ResponseCookie csrfCookie = ResponseCookie.from(JwtAuthenticationFilter.CSRF_COOKIE_NAME, "")
                .httpOnly(false).secure(secure).sameSite("Lax").path("/").maxAge(0).build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
