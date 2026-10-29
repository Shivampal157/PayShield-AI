package com.payshield.config;

import com.payshield.domain.Role;
import com.payshield.domain.UserAccount;
import com.payshield.domain.Wallet;
import com.payshield.dto.PaymentLedgerDtos.ProcessPaymentRequest;
import com.payshield.repository.PaymentTransactionRepository;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.AuthService;
import com.payshield.service.FraudVectorEngine;
import com.payshield.service.PaymentLedgerService;
import com.payshield.service.SecurityAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Safe startup bootstrap initializer:
 * 1. Supports explicit environment-driven first-admin bootstrap (BOOTSTRAP_ADMIN_EMAIL + BOOTSTRAP_ADMIN_PASSWORD)
 *    with NO hardcoded production admin credentials.
 * 2. Seeds local/demo accounts and sample transactions ONLY when payshield.security.demo-seed-enabled=true
 *    (enabled in local/test profiles, disabled in production by default).
 * 3. Initializes OpenSearch "fraud-behavior-vectors" index and seeds the 5 synthetic fraud reference vectors.
 */
@Component
public class BootstrapInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);

    private final AppProperties properties;
    private final UserAccountRepository userRepository;
    private final WalletRepository walletRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final PasswordEncoder passwordEncoder;
    private final FraudVectorEngine fraudVectorEngine;
    private final PaymentLedgerService paymentLedgerService;
    private final SecurityAuditService auditService;

    public BootstrapInitializer(AppProperties properties,
                                UserAccountRepository userRepository,
                                WalletRepository walletRepository,
                                PaymentTransactionRepository paymentRepository,
                                PasswordEncoder passwordEncoder,
                                FraudVectorEngine fraudVectorEngine,
                                PaymentLedgerService paymentLedgerService,
                                SecurityAuditService auditService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.passwordEncoder = passwordEncoder;
        this.fraudVectorEngine = fraudVectorEngine;
        this.paymentLedgerService = paymentLedgerService;
        this.auditService = auditService;
    }

    @Override
    public void run(String... args) {
        bootstrapExplicitAdminIfConfigured();
        fraudVectorEngine.initializeAndSeedOpenSearchIndex();
        if (properties.getSecurity().isDemoSeedEnabled()) {
            seedLocalDemoDatasetIfEmpty();
        }
    }

    private void bootstrapExplicitAdminIfConfigured() {
        String adminEmail = properties.getSecurity().getBootstrapAdminEmail();
        String adminPassword = properties.getSecurity().getBootstrapAdminPassword();
        if (adminEmail != null && !adminEmail.isBlank() && adminPassword != null && !adminPassword.isBlank()) {
            String normalized = adminEmail.trim().toLowerCase();
            if (!userRepository.existsByEmailIgnoreCase(normalized)) {
                UserAccount admin = new UserAccount(
                        UUID.randomUUID(),
                        normalized,
                        "Platform Administrator",
                        passwordEncoder.encode(adminPassword),
                        Role.ADMIN
                );
                userRepository.save(admin);
                Wallet wallet = new Wallet(
                        UUID.randomUUID(),
                        admin.getId(),
                        AuthService.generateAccountNumber("USD", admin.getId()),
                        "USD",
                        new BigDecimal("10000.0000")
                );
                walletRepository.save(wallet);
                auditService.recordEvent(admin.getId(), admin.getEmail(), "ADMIN_BOOTSTRAPPED",
                        "USER_ACCOUNT", admin.getId().toString(), "127.0.0.1",
                        "First administrator account provisioned via BOOTSTRAP_ADMIN_EMAIL environment variable");
                log.info("Bootstrapped first administrator account for email={}", normalized);
            }
        }
    }

    private void seedLocalDemoDatasetIfEmpty() {
        UserAccount alice = ensureDemoUser("alice.morgan@payshield.demo", "Alice Morgan", "PayShieldDemo1", Role.CUSTOMER, "8500.0000");
        UserAccount bob = ensureDemoUser("bob.chen@payshield.demo", "Bob Chen (Merchant Settlement)", "PayShieldDemo1", Role.CUSTOMER, "4200.0000");
        UserAccount analyst = ensureDemoUser("elena.rostova@payshield.demo", "Elena Rostova (Risk Analyst)", "PayShieldDemo1", Role.RISK_ANALYST, "5000.0000");
        ensureDemoUser("marcus.vance@payshield.demo", "Marcus Vance (Security Admin)", "PayShieldDemo1", Role.ADMIN, "10000.0000");

        if (paymentRepository.count() == 0) {
            AuthenticatedUser aliceAuth = new AuthenticatedUser(alice.getId(), alice.getEmail(), alice.getFullName(), alice.getRole(), false);
            AuthenticatedUser bobAuth = new AuthenticatedUser(bob.getId(), bob.getEmail(), bob.getFullName(), bob.getRole(), false);

            // 1. Normal domestic SaaS invoice transfer (APPROVED)
            paymentLedgerService.processPayment(
                    aliceAuth,
                    "seed-idem-normal-001",
                    new ProcessPaymentRequest(
                            bob.getEmail(),
                            new BigDecimal("145.00"),
                            "USD",
                            "Cloud Infrastructure Monthly Invoice",
                            "SAAS_SUBSCRIPTION",
                            "US",
                            "US",
                            18.0,
                            0.98,
                            420
                    ),
                    "127.0.0.1"
            );

            // 2. Normal peer-to-peer reimbursement (APPROVED)
            paymentLedgerService.processPayment(
                    bobAuth,
                    "seed-idem-normal-002",
                    new ProcessPaymentRequest(
                            alice.getEmail(),
                            new BigDecimal("64.50"),
                            "USD",
                            "Team Lunch Split Settlement",
                            "DINING",
                            "US",
                            "US",
                            12.0,
                            0.96,
                            380
                    ),
                    "127.0.0.1"
            );

            // 3. Elevated risk luxury purchase requiring analyst review (REVIEW_REQUIRED / FLAGGED_FRAUD)
            paymentLedgerService.processPayment(
                    aliceAuth,
                    "seed-idem-anomaly-003",
                    new ProcessPaymentRequest(
                            bob.getEmail(),
                            new BigDecimal("4950.00"),
                            "USD",
                            "Offshore Digital Asset Liquidity Bridge",
                            "CRYPTO_EXCHANGE",
                            "US",
                            "SG",
                            8450.0,
                            0.14,
                            5
                    ),
                    "127.0.0.1"
            );

            log.info("Local demo dataset initialized (Customer: alice.morgan@payshield.demo, Risk Analyst: elena.rostova@payshield.demo)");
        }
    }

    private UserAccount ensureDemoUser(String email, String fullName, String rawPassword, Role role, String usdBalance) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            UserAccount user = new UserAccount(
                    UUID.randomUUID(),
                    email,
                    fullName,
                    passwordEncoder.encode(rawPassword),
                    role
            );
            userRepository.save(user);
            Wallet usdWallet = new Wallet(
                    UUID.randomUUID(),
                    user.getId(),
                    AuthService.generateAccountNumber("USD", user.getId()),
                    "USD",
                    new BigDecimal(usdBalance)
            );
            walletRepository.save(usdWallet);
            Wallet eurWallet = new Wallet(
                    UUID.randomUUID(),
                    user.getId(),
                    AuthService.generateAccountNumber("EUR", user.getId()),
                    "EUR",
                    new BigDecimal("2500.0000")
            );
            walletRepository.save(eurWallet);
            return user;
        });
    }
}
