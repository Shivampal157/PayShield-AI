package com.payshield;

import com.payshield.domain.PaymentStatus;
import com.payshield.domain.Role;
import com.payshield.domain.UserAccount;
import com.payshield.domain.Wallet;
import com.payshield.dto.PaymentLedgerDtos.AnalystReviewRequest;
import com.payshield.dto.PaymentLedgerDtos.PaymentTransactionResponse;
import com.payshield.dto.PaymentLedgerDtos.ProcessPaymentRequest;
import com.payshield.dto.PaymentLedgerDtos.ReconciliationReportResponse;
import com.payshield.exception.ApiException;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.AuthService;
import com.payshield.service.PaymentLedgerService;
import com.payshield.service.RagDisputeCopilotService;
import com.payshield.service.RagDisputeCopilotService.DisputeAuditReportResponse;
import com.payshield.service.RedisRiskEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentLedgerAndConcurrencyIntegrationTest {

    @Autowired
    private PaymentLedgerService paymentLedgerService;

    @Autowired
    private RagDisputeCopilotService ragService;

    @Autowired
    private RedisRiskEngine redisRiskEngine;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetVelocity() {
        redisRiskEngine.clearAllForTest();
    }

    private AuthenticatedUser createTestUserWithWallet(String prefix, String currency, BigDecimal initialBalance, Role role) {
        UUID id = UUID.randomUUID();
        String email = prefix + "." + id.toString().substring(0, 8) + "@payshield.test";
        UserAccount user = new UserAccount(id, email, "Test " + prefix, passwordEncoder.encode("TestPass123"), role);
        userRepository.save(user);

        Wallet wallet = new Wallet(
                UUID.randomUUID(),
                user.getId(),
                AuthService.generateAccountNumber(currency, user.getId()),
                currency,
                initialBalance
        );
        walletRepository.save(wallet);
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), false);
    }

    @Test
    @DisplayName("Idempotency-Key deduplicates identical retries, prevents concurrent duplicate execution, and rejects mismatched payloads")
    void idempotencyDeduplicationAndPayloadValidation() {
        AuthenticatedUser sender = createTestUserWithWallet("sender", "USD", new BigDecimal("2000.0000"), Role.CUSTOMER);
        AuthenticatedUser recipient = createTestUserWithWallet("recipient", "USD", new BigDecimal("500.0000"), Role.CUSTOMER);

        String idemKey = "idem-test-" + UUID.randomUUID();
        ProcessPaymentRequest req = new ProcessPaymentRequest(
                recipient.email(),
                new BigDecimal("150.00"),
                "USD",
                "Idempotent Invoice #101",
                "SAAS_SUBSCRIPTION",
                "US",
                "US",
                15.0,
                0.98,
                365
        );

        // 1. First execution creates transaction and debits 150.00
        PaymentTransactionResponse first = paymentLedgerService.processPayment(sender, idemKey, req, "127.0.0.1");
        assertThat(first.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.ledgerEntries()).hasSize(2);

        // 2. Second execution with same Idempotency-Key and identical payload returns idempotent replay without second debit
        PaymentTransactionResponse replay = paymentLedgerService.processPayment(sender, idemKey, req, "127.0.0.1");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.idempotentReplay()).isTrue();

        BigDecimal senderBalanceAfterReplay = paymentLedgerService.getWalletsForUser(sender.userId()).get(0).balance();
        assertThat(senderBalanceAfterReplay).isEqualByComparingTo("1850.0000");

        // 3. Reusing same Idempotency-Key with modified amount throws 409 IDEMPOTENCY_PAYLOAD_MISMATCH
        ProcessPaymentRequest tamperedReq = new ProcessPaymentRequest(
                recipient.email(),
                new BigDecimal("999.00"),
                "USD",
                "Idempotent Invoice #101",
                "SAAS_SUBSCRIPTION",
                "US",
                "US",
                15.0,
                0.98,
                365
        );
        assertThatThrownBy(() -> paymentLedgerService.processPayment(sender, idemKey, tamperedReq, "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    @DisplayName("Concurrent transfers attempting to overspend the same wallet never produce a negative balance or break zero-sum ledger")
    void concurrentTransfersPreventOverspending() throws Exception {
        // Provision sender with $1,500.00 and launch 8 concurrent transfers of $600.00 each ($4,800.00 total attempted)
        AuthenticatedUser sender = createTestUserWithWallet("conc-sender", "USD", new BigDecimal("1500.0000"), Role.CUSTOMER);
        AuthenticatedUser recipient = createTestUserWithWallet("conc-recip", "USD", new BigDecimal("100.0000"), Role.CUSTOMER);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ProcessPaymentRequest req = new ProcessPaymentRequest(
                            recipient.email(),
                            new BigDecimal("600.00"),
                            "USD",
                            "Concurrent Payout #" + idx,
                            "RETAIL",
                            "US",
                            "US",
                            10.0,
                            0.99,
                            400
                    );
                    PaymentTransactionResponse res = paymentLedgerService.processPayment(
                            sender, "conc-idem-" + idx + "-" + UUID.randomUUID(), req, "127.0.0.1");
                    if (res.status() == PaymentStatus.APPROVED) {
                        successCount.incrementAndGet();
                    }
                } catch (ApiException apiEx) {
                    if ("INSUFFICIENT_FUNDS".equals(apiEx.getErrorCode())) {
                        insufficientFundsCount.incrementAndGet();
                    } else {
                        unexpectedErrors.add(apiEx);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(unexpectedErrors).isEmpty();
        // $1,500.00 / $600.00 => exactly 2 transfers can succeed ($1,200.00), leaving $300.00, and 6 must fail with INSUFFICIENT_FUNDS
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(insufficientFundsCount.get()).isEqualTo(6);

        BigDecimal finalSenderBalance = paymentLedgerService.getWalletsForUser(sender.userId()).get(0).balance();
        BigDecimal finalRecipientBalance = paymentLedgerService.getWalletsForUser(recipient.userId()).get(0).balance();
        assertThat(finalSenderBalance).isEqualByComparingTo("300.0000");
        assertThat(finalRecipientBalance).isEqualByComparingTo("1300.0000");

        ReconciliationReportResponse recon = paymentLedgerService.reconcileLedger();
        assertThat(recon.overallBalanced()).isTrue();
    }

    @Test
    @DisplayName("High-risk transfer is quarantined without debiting wallet, audited by RAG Copilot with Redis cache hit/miss, and settled upon Analyst approval")
    void highRiskQuarantineRagAuditAndAnalystApprovalLifecycle() {
        AuthenticatedUser sender = createTestUserWithWallet("risk-sender", "USD", new BigDecimal("10000.0000"), Role.CUSTOMER);
        AuthenticatedUser recipient = createTestUserWithWallet("risk-recip", "USD", new BigDecimal("500.0000"), Role.CUSTOMER);
        AuthenticatedUser analyst = createTestUserWithWallet("analyst", "USD", new BigDecimal("1000.0000"), Role.RISK_ANALYST);

        // 1. Submit high-risk offshore crypto transfer
        ProcessPaymentRequest highRiskReq = new ProcessPaymentRequest(
                recipient.email(),
                new BigDecimal("5200.00"),
                "USD",
                "Offshore Crypto Liquidity Desk",
                "CRYPTO_EXCHANGE",
                "US",
                "SG",
                8900.0,
                0.12,
                3
        );

        PaymentTransactionResponse flaggedTx = paymentLedgerService.processPayment(
                sender, "idem-risk-" + UUID.randomUUID(), highRiskReq, "127.0.0.1");

        assertThat(flaggedTx.status()).isEqualTo(PaymentStatus.FLAGGED_FRAUD);
        assertThat(flaggedTx.riskScore()).isGreaterThanOrEqualTo(0.84);
        assertThat(flaggedTx.ledgerEntries()).isEmpty();

        // Verify sender wallet was NOT debited while quarantined in FLAGGED_FRAUD
        BigDecimal senderBalQuarantined = paymentLedgerService.getWalletsForUser(sender.userId()).get(0).balance();
        assertThat(senderBalQuarantined).isEqualByComparingTo("10000.0000");

        // 2. Run AI Dispute Copilot RAG audit -> first call is CACHE_MISS, second call is CACHE_HIT
        DisputeAuditReportResponse rag1 = ragService.runDisputeRagAudit(analyst, flaggedTx.id(), "127.0.0.1");
        assertThat(rag1.cacheStatus()).isEqualTo("CACHE_MISS");
        assertThat(rag1.llmGenerated()).isFalse();
        assertThat(rag1.generationMode()).isEqualTo("HEURISTIC_OFFLINE_FALLBACK");
        assertThat(rag1.citedCaseIds()).isNotEmpty();
        assertThat(rag1.citedPolicyIds()).contains("POL-EFT-101", "POL-MCC-409", "POL-LED-601");
        // Verify PII redaction in prompt summary
        assertThat(rag1.redactedContextSummary()).doesNotContain(sender.email());
        assertThat(rag1.threePointReport().point1RiskSignalsAndEvidence()).isNotBlank();
        assertThat(rag1.threePointReport().point2RelevantPolicyAndCompliance()).isNotBlank();
        assertThat(rag1.threePointReport().point3RecommendedNextActionAndLimitations()).isNotBlank();

        DisputeAuditReportResponse rag2 = ragService.runDisputeRagAudit(analyst, flaggedTx.id(), "127.0.0.1");
        assertThat(rag2.cacheStatus()).isEqualTo("CACHE_HIT");
        assertThat(rag2.cacheSignatureSha256()).isEqualTo(rag1.cacheSignatureSha256());

        // 3. Risk Analyst reviews and approves the quarantined transaction -> settles double-entry ledger
        PaymentTransactionResponse approvedTx = paymentLedgerService.reviewTransaction(
                analyst,
                flaggedTx.id(),
                new AnalystReviewRequest("APPROVE", "Verified customer identity via hardware security key step-up."),
                "127.0.0.1"
        );
        assertThat(approvedTx.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approvedTx.ledgerEntries()).hasSize(2);

        BigDecimal senderBalSettled = paymentLedgerService.getWalletsForUser(sender.userId()).get(0).balance();
        BigDecimal recipientBalSettled = paymentLedgerService.getWalletsForUser(recipient.userId()).get(0).balance();
        assertThat(senderBalSettled).isEqualByComparingTo("4800.0000");
        assertThat(recipientBalSettled).isEqualByComparingTo("5700.0000");

        // 4. Subsequent RAG audit after status change produces a fresh CACHE_MISS due to context-aware invalidation
        DisputeAuditReportResponse ragAfterApproval = ragService.runDisputeRagAudit(analyst, flaggedTx.id(), "127.0.0.1");
        assertThat(ragAfterApproval.cacheStatus()).isEqualTo("CACHE_MISS");
        assertThat(ragAfterApproval.transactionStatus()).isEqualTo("APPROVED");
    }
}
