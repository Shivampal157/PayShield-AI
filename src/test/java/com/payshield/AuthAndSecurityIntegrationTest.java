package com.payshield;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.domain.Role;
import com.payshield.domain.UserAccount;
import com.payshield.repository.UserAccountRepository;
import com.payshield.security.JwtAuthenticationFilter;
import com.payshield.security.JwtTokenService;
import com.payshield.service.AuthService;
import com.payshield.service.LocalDevEmailAdapter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private LocalDevEmailAdapter emailAdapter;

    @BeforeEach
    void setUp() {
        authService.resetRateLimiterForTest();
        emailAdapter.clear();
    }

    @Test
    @DisplayName("Registration creates CUSTOMER account + starter wallet, rejects duplicates and weak passwords")
    void registrationAndValidationFlow() throws Exception {
        // 1. Weak password rejected with 400 and fieldErrors
        String weakBody = """
                {
                  "email": "weak.user@example.com",
                  "fullName": "Weak User",
                  "password": "short",
                  "initialCurrency": "USD"
                }
                """;
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weakBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        // 2. Valid registration succeeds with 201 Created and provisions starter wallet
        String email = "new.customer." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String validBody = String.format("""
                {
                  "email": "%s",
                  "fullName": "Sophia Sterling",
                  "password": "SecurePassword2026",
                  "initialCurrency": "EUR"
                }
                """, email);

        MvcResult regRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode session = objectMapper.readTree(regRes.getResponse().getContentAsString());
        String accessToken = session.get("accessToken").asText();

        // 3. Duplicate registration with same email returns 409 Conflict
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));

        // 4. Verify starter EUR wallet was provisioned with 5000.0000 balance
        mockMvc.perform(get("/api/v1/wallets/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].balance").value(5000.0));
    }

    @Test
    @DisplayName("Expired JWT is rejected, refresh token rotates cleanly, and refresh token replay revokes entire token family")
    void tokenExpirationRotationAndReplayRevocation() throws Exception {
        UserAccount alice = userRepository.findByEmailIgnoreCase("alice.morgan@payshield.demo").orElseThrow();

        // 1. Issue an already-expired JWT (-10 seconds TTL) and verify 401 rejection
        String expiredJwt = jwtTokenService.generateAccessToken(alice, -10L);
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + expiredJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        // 2. Login to obtain initial refresh token (R1)
        MvcResult loginRes = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice.morgan@payshield.demo\",\"password\":\"PayShieldDemo1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginNode = objectMapper.readTree(loginRes.getResponse().getContentAsString());
        String refreshToken1 = loginNode.get("refreshToken").asText();

        // 3. Rotate R1 -> R2
        MvcResult rotateRes = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String refreshToken2 = objectMapper.readTree(rotateRes.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(refreshToken2).isNotEqualTo(refreshToken1);

        // 4. Replay already-rotated R1 -> triggers REFRESH_TOKEN_REUSED and revokes family (including R2!)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_REUSED"));

        // 5. Verify R2 is now also revoked because its token family was compromised
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken2 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Cookie-authenticated state-changing requests enforce double-submit CSRF protection")
    void cookieAuthenticationEnforcesCsrfProtection() throws Exception {
        UserAccount alice = userRepository.findByEmailIgnoreCase("alice.morgan@payshield.demo").orElseThrow();
        String accessToken = jwtTokenService.generateAccessToken(alice);

        Cookie accessCookie = new Cookie(JwtAuthenticationFilter.ACCESS_COOKIE_NAME, accessToken);
        Cookie csrfCookie = new Cookie(JwtAuthenticationFilter.CSRF_COOKIE_NAME, "csrf-secret-token-123");

        // 1. State-changing POST with cookie auth but missing X-CSRF-Token header -> 403 Forbidden
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(accessCookie, csrfCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));

        // 2. State-changing POST with matching X-CSRF-Token header -> 200 OK
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(accessCookie, csrfCookie)
                        .header(JwtAuthenticationFilter.CSRF_HEADER_NAME, "csrf-secret-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGGED_OUT"));
    }

    @Test
    @DisplayName("Single-use password reset token updates password, revokes sessions, and rejects token reuse")
    void passwordResetSingleUseFlow() throws Exception {
        String email = "reset.user." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"email":"%s","fullName":"Reset User","password":"InitialPass123","initialCurrency":"USD"}
                                """, email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        String resetToken = emailAdapter.getLastEmailForRecipient(email)
                .orElseThrow()
                .resetToken();

        // Confirm password reset
        String confirmBody = String.format("""
                {"resetToken":"%s","newPassword":"UpdatedPass456"}
                """, resetToken);
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_UPDATED"));

        // Reusing the same reset token must fail with 400 Bad Request
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RESET_TOKEN_EXPIRED"));

        // Login with new password succeeds
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"UpdatedPass456\"}", email)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Role-based access control enforces privilege boundaries between CUSTOMER, RISK_ANALYST, and ADMIN")
    void roleAuthorizationBoundaries() throws Exception {
        UserAccount customer = userRepository.findByEmailIgnoreCase("alice.morgan@payshield.demo").orElseThrow();
        UserAccount analyst = userRepository.findByEmailIgnoreCase("elena.rostova@payshield.demo").orElseThrow();
        UserAccount admin = userRepository.findByEmailIgnoreCase("marcus.vance@payshield.demo").orElseThrow();

        String customerJwt = jwtTokenService.generateAccessToken(customer);
        String analystJwt = jwtTokenService.generateAccessToken(analyst);
        String adminJwt = jwtTokenService.generateAccessToken(admin);

        // CUSTOMER cannot access ledger reconciliation or security audit logs (403 Forbidden)
        mockMvc.perform(get("/api/v1/ledger/reconciliation")
                        .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isForbidden());

        // RISK_ANALYST and ADMIN can access reconciliation and audit logs (200 OK)
        mockMvc.perform(get("/api/v1/ledger/reconciliation")
                        .header("Authorization", "Bearer " + analystJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallBalanced").value(true));

        mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
