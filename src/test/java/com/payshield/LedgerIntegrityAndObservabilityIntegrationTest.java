package com.payshield;

import com.payshield.domain.OutboxEvent;
import com.payshield.domain.OutboxStatus;
import com.payshield.domain.PaymentStatus;
import com.payshield.domain.Role;
import com.payshield.domain.UserAccount;
import com.payshield.domain.Wallet;
import com.payshield.dto.PaymentLedgerDtos.LedgerEntryResponse;
import com.payshield.dto.PaymentLedgerDtos.PaymentTransactionResponse;
import com.payshield.dto.PaymentLedgerDtos.ProcessPaymentRequest;
import com.payshield.dto.PaymentLedgerDtos.ReconciliationReportResponse;
import com.payshield.dto.TransactionEventPayload;
import com.payshield.repository.OutboxEventRepository;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.security.JwtTokenService;
import com.payshield.service.AuthService;
import com.payshield.service.OutboxPublisherService;
import com.payshield.service.PaymentLedgerService;
import com.payshield.service.RedisRiskEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerIntegrityAndObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentLedgerService paymentLedgerService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RedisRiskEngine redisRiskEngine;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetState() {
        redisRiskEngine.clearAllForTest();
        outboxPublisherService.setSimulateBrokerFailureForTest(false);
        outboxPublisherService.setEnableLocalLoopbackWhenBrokerOffline(true);
    }

    private UserAccount createUserWithWallet(String prefix, BigDecimal initialBalance, Role role) {
        UUID id = UUID.randomUUID();
        String email = prefix + "." + id.toString().substring(0, 8) + "@payshield.test";
        UserAccount user = new UserAccount(id, email, "User " + prefix, passwordEncoder.encode("StrongPass123"), role);
        userRepository.save(user);
        Wallet wallet = new Wallet(
                UUID.randomUUID(),
                user.getId(),
                AuthService.generateAccountNumber("USD", user.getId()),
                "USD",
                initialBalance
        );
        walletRepository.save(wallet);
        return user;
    }

    @Test
    @DisplayName("Per-wallet SHA-256 hash chain maintains monotonic sequences and cryptographic link continuity")
    void perWalletSha256HashChainContinuity() {
        UserAccount senderAcc = createUserWithWallet("chain-sender", new BigDecimal("3000.0000"), Role.CUSTOMER);
        UserAccount recipAcc = createUserWithWallet("chain-recip", new BigDecimal("500.0000"), Role.CUSTOMER);
        AuthenticatedUser sender = new AuthenticatedUser(senderAcc.getId(), senderAcc.getEmail(), senderAcc.getFullName(), senderAcc.getRole(), false);

        PaymentTransactionResponse tx1 = paymentLedgerService.processPayment(
                sender,
                "chain-idem-1-" + UUID.randomUUID(),
                new ProcessPaymentRequest(recipAcc.getEmail(), new BigDecimal("100.00"), "USD",
                        "Ledger Chain Step 1", "SAAS_SUBSCRIPTION", "US", "US", 10.0, 0.98, 400),
                "127.0.0.1"
        );
        PaymentTransactionResponse tx2 = paymentLedgerService.processPayment(
                sender,
                "chain-idem-2-" + UUID.randomUUID(),
                new ProcessPaymentRequest(recipAcc.getEmail(), new BigDecimal("250.00"), "USD",
                        "Ledger Chain Step 2", "SAAS_SUBSCRIPTION", "US", "US", 12.0, 0.98, 400),
                "127.0.0.1"
        );

        LedgerEntryResponse senderDebit1 = tx1.ledgerEntries().stream()
                .filter(e -> e.walletId().equals(tx1.senderWalletId()))
                .findFirst().orElseThrow();
        LedgerEntryResponse senderDebit2 = tx2.ledgerEntries().stream()
                .filter(e -> e.walletId().equals(tx2.senderWalletId()))
                .findFirst().orElseThrow();

        assertThat(senderDebit1.walletSequence()).isEqualTo(1L);
        assertThat(senderDebit2.walletSequence()).isEqualTo(2L);
        assertThat(senderDebit2.previousHash()).isEqualTo(senderDebit1.entryHash());
        assertThat(senderDebit2.entryHash()).hasSize(64).isNotEqualTo(senderDebit1.entryHash());

        ReconciliationReportResponse recon = paymentLedgerService.reconcileLedger();
        assertThat(recon.cryptographicHashChainIntact()).isTrue();
        assertThat(recon.brokenHashChainLinks()).isZero();
        assertThat(recon.overallBalanced()).isTrue();
    }

    @Test
    @DisplayName("Reconciliation detects direct SQL tampering on immutable ledger rows via SHA-256 digest mismatch")
    void unauthorizedDatabaseLedgerTamperingIsDetected() {
        UserAccount senderAcc = createUserWithWallet("tamper-sender", new BigDecimal("1500.0000"), Role.CUSTOMER);
        UserAccount recipAcc = createUserWithWallet("tamper-recip", new BigDecimal("500.0000"), Role.CUSTOMER);
        AuthenticatedUser sender = new AuthenticatedUser(senderAcc.getId(), senderAcc.getEmail(), senderAcc.getFullName(), senderAcc.getRole(), false);

        PaymentTransactionResponse tx = paymentLedgerService.processPayment(
                sender,
                "tamper-idem-" + UUID.randomUUID(),
                new ProcessPaymentRequest(recipAcc.getEmail(), new BigDecimal("80.00"), "USD",
                        "Integrity Audit Target", "RETAIL", "US", "US", 8.0, 0.99, 500),
                "127.0.0.1"
        );

        LedgerEntryResponse debitEntry = tx.ledgerEntries().get(0);
        BigDecimal originalBalanceAfter = debitEntry.balanceAfter();

        try {
            // Simulate an attacker with raw SQL access altering balance_after without breaking debit/credit sum
            jdbcTemplate.update(
                    "UPDATE ledger_entries SET balance_after = ? WHERE id = ?",
                    originalBalanceAfter.add(new BigDecimal("500.0000")),
                    debitEntry.id()
            );

            ReconciliationReportResponse tamperedReport = paymentLedgerService.reconcileLedger();
            assertThat(tamperedReport.cryptographicHashChainIntact()).isFalse();
            assertThat(tamperedReport.brokenHashChainLinks()).isGreaterThanOrEqualTo(1L);
            assertThat(tamperedReport.overallBalanced()).isFalse();
            assertThat(tamperedReport.anomalies())
                    .anyMatch(msg -> msg.contains("Cryptographic hash-chain violation"));
        } finally {
            // Restore original immutable snapshot so shared context remains clean for other tests
            jdbcTemplate.update(
                    "UPDATE ledger_entries SET balance_after = ? WHERE id = ?",
                    originalBalanceAfter,
                    debitEntry.id()
            );
        }

        assertThat(paymentLedgerService.reconcileLedger().cryptographicHashChainIntact()).isTrue();
    }

    @Test
    @DisplayName("Payment HTTP wire protocol exposes X-Idempotent-Replay, X-Risk-Decision, Server-Timing, and RFC 9457 error type")
    void paymentWireHeadersAndStructuredErrors() throws Exception {
        UserAccount senderAcc = createUserWithWallet("wire-sender", new BigDecimal("2000.0000"), Role.CUSTOMER);
        UserAccount recipAcc = createUserWithWallet("wire-recip", new BigDecimal("500.0000"), Role.CUSTOMER);
        String jwt = jwtTokenService.generateAccessToken(senderAcc);
        String idemKey = "wire-idem-" + UUID.randomUUID();

        String payload = String.format("""
                {
                  "recipientIdentifier": "%s",
                  "amount": 95.25,
                  "currency": "USD",
                  "merchantName": "Wire Protocol Verification",
                  "merchantCategory": "SAAS_SUBSCRIPTION",
                  "sourceCountry": "US",
                  "destinationCountry": "US",
                  "ipDistanceKm": 14.0,
                  "deviceTrustScore": 0.97,
                  "accountAgeDays": 365
                }
                """, recipAcc.getEmail());

        // 1. Fresh execution -> 201 Created + X-Idempotent-Replay: false + Server-Timing
        mockMvc.perform(post("/api/v1/payments/process")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(header().string("X-Risk-Decision", "APPROVED"))
                .andExpect(header().string("Server-Timing", containsString("ledger;dur=")));

        // 2. Duplicate retry -> 200 OK + X-Idempotent-Replay: true
        mockMvc.perform(post("/api/v1/payments/process")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 3. Missing Idempotency-Key returns RFC 9457 problem type URN
        mockMvc.perform(post("/api/v1/payments/process")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payshield:error:missing-header"))
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("Micrometer percentile telemetry and Actuator health endpoint expose live execution metrics")
    void micrometerTelemetryAndActuatorHealth() throws Exception {
        UserAccount analyst = userRepository.findByEmailIgnoreCase("elena.rostova@payshield.demo").orElseThrow();
        String analystJwt = jwtTokenService.generateAccessToken(analyst);

        mockMvc.perform(get("/api/v1/risk/metrics")
                        .header("Authorization", "Bearer " + analystJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetry.recordedExecutions").isNumber())
                .andExpect(jsonPath("$.telemetry.p50LatencyMs").isNumber())
                .andExpect(jsonPath("$.telemetry.p95LatencyMs").isNumber())
                .andExpect(jsonPath("$.telemetry.p99LatencyMs").isNumber());

        mockMvc.perform(get("/actuator/health")
                        .header("Authorization", "Bearer " + analystJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Operator DLT replay endpoint enforces RBAC and recovers DEAD_LETTER outbox events")
    void operatorOutboxDltReplayEndpoint() throws Exception {
        UserAccount customer = userRepository.findByEmailIgnoreCase("alice.morgan@payshield.demo").orElseThrow();
        UserAccount analyst = userRepository.findByEmailIgnoreCase("elena.rostova@payshield.demo").orElseThrow();
        String customerJwt = jwtTokenService.generateAccessToken(customer);
        String analystJwt = jwtTokenService.generateAccessToken(analyst);

        // CUSTOMER must be denied (403)
        mockMvc.perform(post("/api/v1/risk/outbox/replay-dlt")
                        .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isForbidden());

        // Create and force an event into DEAD_LETTER
        UUID eventId = UUID.randomUUID();
        TransactionEventPayload payload = new TransactionEventPayload(
                eventId,
                TransactionEventPayload.CURRENT_SCHEMA_VERSION,
                "PAYMENT_APPROVED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("42.0000"),
                "USD",
                PaymentStatus.APPROVED,
                0.11,
                "ps-dlt-replay-test",
                Instant.now()
        );
        transactionTemplate.execute(s -> outboxPublisherService.enqueueTransactionEvent(payload));
        outboxPublisherService.setSimulateBrokerFailureForTest(true);
        for (int i = 0; i < 3; i++) {
            outboxPublisherService.processSingleOutboxEvent(outboxRepository.findById(eventId).orElseThrow());
        }
        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);

        // Restore broker and trigger REST DLT replay as RISK_ANALYST
        outboxPublisherService.setSimulateBrokerFailureForTest(false);
        mockMvc.perform(post("/api/v1/risk/outbox/replay-dlt")
                        .header("Authorization", "Bearer " + analystJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayedDeadLetterEvents").isNumber())
                .andExpect(jsonPath("$.remainingDeadLetterEvents").value(0));

        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }
}
