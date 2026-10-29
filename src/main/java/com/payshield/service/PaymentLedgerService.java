package com.payshield.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.CorrelationIdFilter;
import com.payshield.domain.*;
import com.payshield.dto.PaymentLedgerDtos.*;
import com.payshield.dto.TransactionEventPayload;
import com.payshield.exception.ApiException;
import com.payshield.repository.LedgerEntryRepository;
import com.payshield.repository.PaymentTransactionRepository;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.FraudVectorEngine.ExplainableRiskFactor;
import com.payshield.service.FraudVectorEngine.FeatureContext;
import com.payshield.service.FraudVectorEngine.FraudEvaluationResult;
import com.payshield.service.FraudVectorEngine.SimilarCaseMatch;
import com.payshield.service.RedisRiskEngine.VelocityCheckResult;
import com.payshield.util.HashUtils;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PaymentLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedgerService.class);

    private final WalletRepository walletRepository;
    private final UserAccountRepository userRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final RedisRiskEngine redisRiskEngine;
    private final FraudVectorEngine fraudVectorEngine;
    private final OutboxPublisherService outboxPublisherService;
    private final SecurityAuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public PaymentLedgerService(WalletRepository walletRepository,
                                UserAccountRepository userRepository,
                                PaymentTransactionRepository paymentRepository,
                                LedgerEntryRepository ledgerRepository,
                                RedisRiskEngine redisRiskEngine,
                                FraudVectorEngine fraudVectorEngine,
                                OutboxPublisherService outboxPublisherService,
                                SecurityAuditService auditService,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.redisRiskEngine = redisRiskEngine;
        this.fraudVectorEngine = fraudVectorEngine;
        this.outboxPublisherService = outboxPublisherService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Executes a payment transfer with Redis SETNX coordination, durable database idempotency verification,
     * deterministic pessimistic row locking, 8-D vector fraud evaluation, atomic double-entry ledger posting,
     * and transactional outbox event publication.
     */
    public PaymentTransactionResponse processPayment(AuthenticatedUser actor,
                                                     String rawIdempotencyKey,
                                                     ProcessPaymentRequest request,
                                                     String ipAddress) {
        long startNanos = System.nanoTime();
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank() || rawIdempotencyKey.trim().length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required and must be between 1 and 128 characters.");
        }
        String idempotencyKey = rawIdempotencyKey.trim();
        String currency = request.currency().trim().toUpperCase();
        if (!SupportedCurrency.isValid(currency)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Currency must be one of USD, EUR, or GBP.");
        }

        BigDecimal amount = request.amount().setScale(4, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Transfer amount must be positive.");
        }

        Wallet senderWalletPreview = walletRepository.findByUserIdAndCurrencyIgnoreCase(actor.userId(), currency)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SENDER_WALLET_NOT_FOUND",
                        "No active " + currency + " wallet found for current user."));

        Wallet recipientWalletPreview = resolveRecipientWallet(request.recipientIdentifier(), currency);
        if (senderWalletPreview.getId().equals(recipientWalletPreview.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED",
                    "Sender and recipient wallets cannot be identical.");
        }

        String requestHash = computeRequestPayloadHash(recipientWalletPreview.getId(), amount, currency, request);

        // 1. Pre-check durable database idempotency record
        Optional<PaymentTransaction> existingPre = paymentRepository
                .findBySenderWalletIdAndIdempotencyKey(senderWalletPreview.getId(), idempotencyKey);
        if (existingPre.isPresent()) {
            return verifyAndBuildIdempotentReplay(existingPre.get(), requestHash);
        }

        // 2. Fast-path Redis SETNX lock coordination
        boolean lockAcquired = redisRiskEngine.acquireIdempotencyLock(
                senderWalletPreview.getId(), idempotencyKey, Duration.ofSeconds(30));

        try {
            return transactionTemplate.execute(status -> executeTransactionalTransfer(
                    actor,
                    senderWalletPreview.getId(),
                    recipientWalletPreview.getId(),
                    idempotencyKey,
                    requestHash,
                    amount,
                    currency,
                    request,
                    ipAddress,
                    startNanos,
                    lockAcquired
            ));
        } finally {
            if (lockAcquired) {
                redisRiskEngine.releaseIdempotencyLock(senderWalletPreview.getId(), idempotencyKey);
            }
        }
    }

    private PaymentTransactionResponse executeTransactionalTransfer(AuthenticatedUser actor,
                                                                    UUID senderWalletId,
                                                                    UUID recipientWalletId,
                                                                    String idempotencyKey,
                                                                    String requestHash,
                                                                    BigDecimal amount,
                                                                    String currency,
                                                                    ProcessPaymentRequest request,
                                                                    String ipAddress,
                                                                    long startNanos,
                                                                    boolean lockAcquired) {
        // Lock both wallets in deterministic UUID order to prevent deadlocks under concurrent bidirectional transfers
        UUID firstLockId = senderWalletId.compareTo(recipientWalletId) < 0 ? senderWalletId : recipientWalletId;
        UUID secondLockId = senderWalletId.compareTo(recipientWalletId) < 0 ? recipientWalletId : senderWalletId;

        Wallet firstLocked = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found."));
        Wallet secondLocked = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found."));

        Wallet senderWallet = firstLocked.getId().equals(senderWalletId) ? firstLocked : secondLocked;
        Wallet recipientWallet = firstLocked.getId().equals(recipientWalletId) ? firstLocked : secondLocked;

        // Re-check durable DB idempotency inside the pessimistic wallet lock!
        // This guarantees that concurrent duplicate requests with the same Idempotency-Key serialize cleanly.
        Optional<PaymentTransaction> existingAfterLock = paymentRepository
                .findBySenderWalletIdAndIdempotencyKey(senderWalletId, idempotencyKey);
        if (existingAfterLock.isPresent()) {
            return verifyAndBuildIdempotentReplay(existingAfterLock.get(), requestHash);
        }

        if (!"ACTIVE".equalsIgnoreCase(senderWallet.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WALLET_FROZEN", "Sender wallet is not active.");
        }

        if (senderWallet.getBalance().compareTo(amount) < 0) {
            auditService.recordEvent(actor.userId(), actor.email(), "PAYMENT_INSUFFICIENT_FUNDS",
                    "WALLET", senderWallet.getId().toString(), ipAddress,
                    "Attempted transfer of " + amount + " " + currency + " with balance " + senderWallet.getBalance());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    String.format("Insufficient wallet balance. Available: %s %s, Requested: %s %s.",
                            senderWallet.getBalance().toPlainString(), currency, amount.toPlainString(), currency));
        }

        UserAccount senderUser = userRepository.findById(senderWallet.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Sender user not found."));
        UserAccount recipientUser = userRepository.findById(recipientWallet.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Recipient user not found."));

        // Evaluate sliding-window velocity & 8-D behavioral vector risk
        VelocityCheckResult velocityResult = redisRiskEngine.recordAndCheckVelocity(actor.userId());

        int defaultAccountAge = (int) Math.max(1, ChronoUnit.DAYS.between(senderUser.getCreatedAt(), Instant.now()));
        int effectiveAccountAge = request.accountAgeDays() != null
                ? request.accountAgeDays()
                : Math.max(defaultAccountAge, 180);
        double effectiveDeviceTrust = request.deviceTrustScore() != null
                ? request.deviceTrustScore()
                : 0.95;

        FeatureContext featureContext = new FeatureContext(
                amount,
                currency,
                request.merchantCategory().trim().toUpperCase(),
                request.sourceCountry().trim().toUpperCase(),
                request.destinationCountry().trim().toUpperCase(),
                request.ipDistanceKm(),
                effectiveDeviceTrust,
                effectiveAccountAge,
                velocityResult.attemptsInWindow(),
                Instant.now()
        );

        FraudEvaluationResult fraudEval = fraudVectorEngine.evaluateTransaction(featureContext, velocityResult.exceeded());

        UUID txId = UUID.randomUUID();
        String correlationId = CorrelationIdFilter.currentCorrelationId();
        Instant now = Instant.now();

        PaymentTransaction tx = new PaymentTransaction();
        tx.setId(txId);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRequestHash(requestHash);
        tx.setSenderWalletId(senderWallet.getId());
        tx.setRecipientWalletId(recipientWallet.getId());
        tx.setSenderEmail(senderUser.getEmail());
        tx.setRecipientEmail(recipientUser.getEmail());
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setMerchantName(request.merchantName().trim());
        tx.setMerchantCategory(request.merchantCategory().trim().toUpperCase());
        tx.setSourceCountry(request.sourceCountry().trim().toUpperCase());
        tx.setDestinationCountry(request.destinationCountry().trim().toUpperCase());
        tx.setIpDistanceKm(request.ipDistanceKm());
        tx.setDeviceTrustScore(effectiveDeviceTrust);
        tx.setAccountAgeDays(effectiveAccountAge);
        tx.setVelocityCount(velocityResult.attemptsInWindow());
        tx.setStatus(fraudEval.recommendedStatus());
        tx.setRiskScore(fraudEval.riskScore());
        tx.setFeatureVectorJson(writeJson(fraudEval.featureVector()));
        tx.setRiskFactorsJson(writeJson(fraudEval.riskFactors()));
        tx.setMatchedCasesJson(writeJson(fraudEval.topSimilarCases()));
        tx.setScoringSource(fraudEval.scoringSource());
        tx.setCorrelationId(correlationId);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);

        List<LedgerEntry> createdEntries = new ArrayList<>();

        // Explicit Fraud vs. Ledger Execution Contract:
        // Only APPROVED transactions atomically debit/credit balances and post immutable ledger entries.
        // FLAGGED_FRAUD and REVIEW_REQUIRED transfers are quarantined prior to balance settlement.
        if (fraudEval.recommendedStatus() == PaymentStatus.APPROVED) {
            BigDecimal newSenderBalance = senderWallet.getBalance().subtract(amount).setScale(4, RoundingMode.HALF_UP);
            BigDecimal newRecipientBalance = recipientWallet.getBalance().add(amount).setScale(4, RoundingMode.HALF_UP);

            senderWallet.setBalance(newSenderBalance);
            recipientWallet.setBalance(newRecipientBalance);
            walletRepository.save(senderWallet);
            walletRepository.save(recipientWallet);

            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            tx.setProcessingLatencyMs(latencyMs);
            paymentRepository.save(tx);

            LedgerEntry debit = new LedgerEntry(
                    UUID.randomUUID(),
                    txId,
                    senderWallet.getId(),
                    senderWallet.getAccountNumber(),
                    LedgerEntryType.DEBIT,
                    amount,
                    currency,
                    newSenderBalance,
                    "Transfer to " + recipientWallet.getAccountNumber() + " (" + tx.getMerchantName() + ")"
            );
            LedgerEntry credit = new LedgerEntry(
                    UUID.randomUUID(),
                    txId,
                    recipientWallet.getId(),
                    recipientWallet.getAccountNumber(),
                    LedgerEntryType.CREDIT,
                    amount,
                    currency,
                    newRecipientBalance,
                    "Transfer from " + senderWallet.getAccountNumber() + " (" + tx.getMerchantName() + ")"
            );
            createdEntries.add(ledgerRepository.save(debit));
            createdEntries.add(ledgerRepository.save(credit));
        } else {
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            tx.setProcessingLatencyMs(latencyMs);
            paymentRepository.save(tx);
        }

        // Write versioned event to transactional outbox inside the same database transaction
        outboxPublisherService.enqueueTransactionEvent(new TransactionEventPayload(
                UUID.randomUUID(),
                TransactionEventPayload.CURRENT_SCHEMA_VERSION,
                "PAYMENT_" + tx.getStatus().name(),
                tx.getId(),
                tx.getSenderWalletId(),
                tx.getRecipientWalletId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                tx.getRiskScore(),
                correlationId,
                now
        ));

        auditService.recordEvent(
                actor.userId(),
                actor.email(),
                "PAYMENT_" + tx.getStatus().name(),
                "PAYMENT_TRANSACTION",
                tx.getId().toString(),
                ipAddress,
                String.format("Amount=%s %s status=%s riskScore=%.4f velocity=%d/%ds source=%s",
                        amount.toPlainString(), currency, tx.getStatus(), tx.getRiskScore(),
                        velocityResult.attemptsInWindow(), velocityResult.windowSeconds(), tx.getScoringSource())
        );

        return toTransactionResponse(tx, createdEntries, false);
    }

    /**
     * Risk Analyst or Admin review action on a quarantined (FLAGGED_FRAUD or REVIEW_REQUIRED) transaction.
     * - APPROVE: Atomically locks wallets, verifies sufficient funds, posts DEBIT/CREDIT ledger entries, and marks APPROVED.
     * - DECLINE: Marks transaction DECLINED without touching wallet balances.
     */
    @Transactional
    public PaymentTransactionResponse reviewTransaction(AuthenticatedUser analyst,
                                                        UUID transactionId,
                                                        AnalystReviewRequest reviewRequest,
                                                        String ipAddress) {
        PaymentTransaction tx = paymentRepository.findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "Payment transaction not found."));

        if (tx.getStatus() != PaymentStatus.FLAGGED_FRAUD && tx.getStatus() != PaymentStatus.REVIEW_REQUIRED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "Only FLAGGED_FRAUD or REVIEW_REQUIRED transactions can be reviewed. Current status: " + tx.getStatus());
        }

        String decision = reviewRequest.decision().trim().toUpperCase();
        Instant now = Instant.now();
        List<LedgerEntry> entries = new ArrayList<>();

        if ("APPROVE".equals(decision)) {
            UUID senderId = tx.getSenderWalletId();
            UUID recipientId = tx.getRecipientWalletId();
            UUID firstId = senderId.compareTo(recipientId) < 0 ? senderId : recipientId;
            UUID secondId = senderId.compareTo(recipientId) < 0 ? recipientId : senderId;

            Wallet first = walletRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found."));
            Wallet second = walletRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found."));

            Wallet senderWallet = first.getId().equals(senderId) ? first : second;
            Wallet recipientWallet = first.getId().equals(recipientId) ? first : second;

            if (senderWallet.getBalance().compareTo(tx.getAmount()) < 0) {
                tx.setStatus(PaymentStatus.FAILED);
                tx.setAnalystDecision("APPROVE_FAILED_INSUFFICIENT_FUNDS");
                tx.setAnalystNotes(reviewRequest.notes().trim());
                tx.setReviewedBy(analyst.email());
                tx.setReviewedAt(now);
                tx.setUpdatedAt(now);
                paymentRepository.save(tx);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                        "Sender no longer has sufficient funds to settle this transaction.");
            }

            BigDecimal newSenderBalance = senderWallet.getBalance().subtract(tx.getAmount()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal newRecipientBalance = recipientWallet.getBalance().add(tx.getAmount()).setScale(4, RoundingMode.HALF_UP);
            senderWallet.setBalance(newSenderBalance);
            recipientWallet.setBalance(newRecipientBalance);
            walletRepository.save(senderWallet);
            walletRepository.save(recipientWallet);

            LedgerEntry debit = new LedgerEntry(
                    UUID.randomUUID(),
                    tx.getId(),
                    senderWallet.getId(),
                    senderWallet.getAccountNumber(),
                    LedgerEntryType.DEBIT,
                    tx.getAmount(),
                    tx.getCurrency(),
                    newSenderBalance,
                    "Analyst-approved settlement to " + recipientWallet.getAccountNumber()
            );
            LedgerEntry credit = new LedgerEntry(
                    UUID.randomUUID(),
                    tx.getId(),
                    recipientWallet.getId(),
                    recipientWallet.getAccountNumber(),
                    LedgerEntryType.CREDIT,
                    tx.getAmount(),
                    tx.getCurrency(),
                    newRecipientBalance,
                    "Analyst-approved settlement from " + senderWallet.getAccountNumber()
            );
            entries.add(ledgerRepository.save(debit));
            entries.add(ledgerRepository.save(credit));

            tx.setStatus(PaymentStatus.APPROVED);
        } else {
            tx.setStatus(PaymentStatus.DECLINED);
        }

        tx.setAnalystDecision(decision);
        tx.setAnalystNotes(reviewRequest.notes().trim());
        tx.setReviewedBy(analyst.email());
        tx.setReviewedAt(now);
        tx.setUpdatedAt(now);
        paymentRepository.save(tx);

        // Invalidate cached RAG report because transaction status & analyst decision changed
        redisRiskEngine.invalidateRagCacheForTransaction(tx.getId());

        outboxPublisherService.enqueueTransactionEvent(new TransactionEventPayload(
                UUID.randomUUID(),
                TransactionEventPayload.CURRENT_SCHEMA_VERSION,
                "PAYMENT_ANALYST_" + decision,
                tx.getId(),
                tx.getSenderWalletId(),
                tx.getRecipientWalletId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                tx.getRiskScore(),
                CorrelationIdFilter.currentCorrelationId(),
                now
        ));

        auditService.recordEvent(
                analyst.userId(),
                analyst.email(),
                "RISK_ANALYST_REVIEW",
                "PAYMENT_TRANSACTION",
                tx.getId().toString(),
                ipAddress,
                "Analyst decision=" + decision + " newStatus=" + tx.getStatus() + " notes=" + tx.getAnalystNotes()
        );

        return toTransactionResponse(tx, entries, false);
    }

    /**
     * Simulates a rapid burst of 7 payment attempts for the authenticated user to demonstrate
     * Redis sliding-window velocity enforcement (>5 attempts in 60s).
     */
    public VelocitySimulationResponse simulateVelocityAttack(AuthenticatedUser actor, String ipAddress) {
        List<Wallet> userWallets = walletRepository.findByUserIdOrderByCurrencyAsc(actor.userId());
        if (userWallets.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_WALLET", "User has no wallet for velocity simulation.");
        }
        String currency = userWallets.get(0).getCurrency();

        // Find a counterfactual recipient wallet in the same currency
        Wallet recipient = walletRepository.findAll().stream()
                .filter(w -> w.getCurrency().equalsIgnoreCase(currency) && !w.getUserId().equals(actor.userId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_RECIPIENT_WALLET",
                        "No counterfactual recipient wallet available in " + currency + "."));

        List<PaymentTransactionResponse> results = new ArrayList<>();
        int approved = 0;
        int flagged = 0;

        for (int i = 1; i <= 7; i++) {
            ProcessPaymentRequest req = new ProcessPaymentRequest(
                    recipient.getAccountNumber(),
                    new BigDecimal("12.50"),
                    currency,
                    "Velocity Probe Counterparty #" + i,
                    "ELECTRONICS",
                    "US",
                    "US",
                    45.0,
                    0.85,
                    180
            );
            String idemKey = "vel-sim-" + UUID.randomUUID();
            PaymentTransactionResponse txRes = processPayment(actor, idemKey, req, ipAddress);
            results.add(txRes);
            if (txRes.status() == PaymentStatus.APPROVED) {
                approved++;
            } else {
                flagged++;
            }
        }

        return new VelocitySimulationResponse(
                results.size(),
                approved,
                flagged,
                5,
                60,
                redisRiskEngine.isRedisHealthy() ? "REDIS_LUA_ZSET" : "LOCAL_CONCURRENT_FALLBACK",
                results
        );
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> getWalletsForUser(UUID userId) {
        UserAccount owner = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found."));
        return walletRepository.findByUserIdOrderByCurrencyAsc(userId).stream()
                .map(w -> new WalletResponse(
                        w.getId(),
                        w.getUserId(),
                        owner.getEmail(),
                        w.getAccountNumber(),
                        w.getCurrency(),
                        w.getBalance(),
                        w.getStatus(),
                        w.getUpdatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentTransactionResponse getTransactionById(AuthenticatedUser actor, UUID transactionId) {
        PaymentTransaction tx = paymentRepository.findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "Payment transaction not found."));
        enforceTransactionReadAuthorization(actor, tx);
        List<LedgerEntry> entries = ledgerRepository.findByTransactionIdOrderByEntryTypeAsc(tx.getId());
        return toTransactionResponse(tx, entries, false);
    }

    @Transactional(readOnly = true)
    public Page<PaymentTransactionResponse> listTransactions(AuthenticatedUser actor,
                                                             PaymentStatus statusFilter,
                                                             String currencyFilter,
                                                             String searchQuery,
                                                             Pageable pageable) {
        Set<UUID> actorWalletIds = new HashSet<>();
        if (actor.role() == Role.CUSTOMER) {
            walletRepository.findByUserIdOrderByCurrencyAsc(actor.userId())
                    .forEach(w -> actorWalletIds.add(w.getId()));
        }

        Specification<PaymentTransaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor.role() == Role.CUSTOMER) {
                if (actorWalletIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    Predicate isSender = root.get("senderWalletId").in(actorWalletIds);
                    Predicate isRecipient = root.get("recipientWalletId").in(actorWalletIds);
                    predicates.add(cb.or(isSender, isRecipient));
                }
            }
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (currencyFilter != null && !currencyFilter.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("currency")), currencyFilter.trim().toUpperCase()));
            }
            if (searchQuery != null && !searchQuery.isBlank()) {
                String pattern = "%" + searchQuery.trim().toLowerCase() + "%";
                Predicate merchantMatch = cb.like(cb.lower(root.get("merchantName")), pattern);
                Predicate senderMatch = cb.like(cb.lower(root.get("senderEmail")), pattern);
                Predicate recipientMatch = cb.like(cb.lower(root.get("recipientEmail")), pattern);
                Predicate idemMatch = cb.like(cb.lower(root.get("idempotencyKey")), pattern);
                predicates.add(cb.or(merchantMatch, senderMatch, recipientMatch, idemMatch));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return paymentRepository.findAll(spec, pageable).map(tx -> {
            List<LedgerEntry> entries = ledgerRepository.findByTransactionIdOrderByEntryTypeAsc(tx.getId());
            return toTransactionResponse(tx, entries, false);
        });
    }

    @Transactional(readOnly = true)
    public Page<PaymentTransactionResponse> listSuspiciousTransactions(PaymentStatus statusFilter, Pageable pageable) {
        List<PaymentStatus> statuses = statusFilter != null
                ? List.of(statusFilter)
                : List.of(PaymentStatus.FLAGGED_FRAUD, PaymentStatus.REVIEW_REQUIRED, PaymentStatus.DECLINED);
        return paymentRepository.findByStatusIn(statuses, pageable).map(tx -> {
            List<LedgerEntry> entries = ledgerRepository.findByTransactionIdOrderByEntryTypeAsc(tx.getId());
            return toTransactionResponse(tx, entries, false);
        });
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> listLedgerEntries(AuthenticatedUser actor,
                                                       LedgerEntryType entryType,
                                                       String currency,
                                                       UUID transactionId,
                                                       Pageable pageable) {
        Set<UUID> actorWalletIds = new HashSet<>();
        if (actor.role() == Role.CUSTOMER) {
            walletRepository.findByUserIdOrderByCurrencyAsc(actor.userId())
                    .forEach(w -> actorWalletIds.add(w.getId()));
        }

        Specification<LedgerEntry> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor.role() == Role.CUSTOMER) {
                if (actorWalletIds.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(root.get("walletId").in(actorWalletIds));
                }
            }
            if (entryType != null) {
                predicates.add(cb.equal(root.get("entryType"), entryType));
            }
            if (currency != null && !currency.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("currency")), currency.trim().toUpperCase()));
            }
            if (transactionId != null) {
                predicates.add(cb.equal(root.get("transactionId"), transactionId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return ledgerRepository.findAll(spec, pageable).map(this::toLedgerEntryResponse);
    }

    /**
     * Performs a complete double-entry ledger reconciliation across all currencies and transactions.
     */
    @Transactional(readOnly = true)
    public ReconciliationReportResponse reconcileLedger() {
        List<LedgerEntry> allEntries = ledgerRepository.findAll();
        List<PaymentTransaction> allTransactions = paymentRepository.findAll();

        Map<String, BigDecimal> debitsByCurrency = new TreeMap<>();
        Map<String, BigDecimal> creditsByCurrency = new TreeMap<>();
        Map<String, Long> debitCountByCurrency = new TreeMap<>();
        Map<String, Long> creditCountByCurrency = new TreeMap<>();

        for (String curr : SupportedCurrency.SUPPORTED) {
            debitsByCurrency.put(curr, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            creditsByCurrency.put(curr, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            debitCountByCurrency.put(curr, 0L);
            creditCountByCurrency.put(curr, 0L);
        }

        Map<UUID, List<LedgerEntry>> entriesByTx = new HashMap<>();
        for (LedgerEntry entry : allEntries) {
            String curr = entry.getCurrency().toUpperCase();
            entriesByTx.computeIfAbsent(entry.getTransactionId(), k -> new ArrayList<>()).add(entry);
            if (entry.getEntryType() == LedgerEntryType.DEBIT) {
                debitsByCurrency.put(curr, debitsByCurrency.getOrDefault(curr, BigDecimal.ZERO).add(entry.getAmount()));
                debitCountByCurrency.put(curr, debitCountByCurrency.getOrDefault(curr, 0L) + 1);
            } else {
                creditsByCurrency.put(curr, creditsByCurrency.getOrDefault(curr, BigDecimal.ZERO).add(entry.getAmount()));
                creditCountByCurrency.put(curr, creditCountByCurrency.getOrDefault(curr, 0L) + 1);
            }
        }

        List<String> anomalies = new ArrayList<>();
        long totalApproved = 0;
        long approvedWithCompleteEntries = 0;
        long unapprovedWithEntries = 0;

        for (PaymentTransaction tx : allTransactions) {
            List<LedgerEntry> txEntries = entriesByTx.getOrDefault(tx.getId(), List.of());
            if (tx.getStatus() == PaymentStatus.APPROVED) {
                totalApproved++;
                boolean hasDebit = txEntries.stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.DEBIT
                        && e.getAmount().compareTo(tx.getAmount()) == 0);
                boolean hasCredit = txEntries.stream().anyMatch(e -> e.getEntryType() == LedgerEntryType.CREDIT
                        && e.getAmount().compareTo(tx.getAmount()) == 0);
                if (txEntries.size() == 2 && hasDebit && hasCredit) {
                    approvedWithCompleteEntries++;
                } else {
                    anomalies.add("APPROVED transaction " + tx.getId() + " is missing balanced DEBIT/CREDIT entries.");
                }
            } else {
                if (!txEntries.isEmpty()) {
                    unapprovedWithEntries++;
                    anomalies.add("Non-settled transaction " + tx.getId() + " (" + tx.getStatus() + ") has unexpected ledger entries.");
                }
            }
        }

        List<CurrencyReconciliationSummary> summaries = new ArrayList<>();
        boolean allCurrenciesBalanced = true;

        for (String curr : debitsByCurrency.keySet()) {
            BigDecimal debits = debitsByCurrency.get(curr).setScale(4, RoundingMode.HALF_UP);
            BigDecimal credits = creditsByCurrency.get(curr).setScale(4, RoundingMode.HALF_UP);
            BigDecimal diff = credits.subtract(debits).setScale(4, RoundingMode.HALF_UP);
            boolean balanced = diff.compareTo(BigDecimal.ZERO) == 0
                    && Objects.equals(debitCountByCurrency.get(curr), creditCountByCurrency.get(curr));
            if (!balanced) {
                allCurrenciesBalanced = false;
                anomalies.add("Currency " + curr + " has non-zero net difference: " + diff.toPlainString());
            }
            summaries.add(new CurrencyReconciliationSummary(
                    curr,
                    debits,
                    credits,
                    diff,
                    debitCountByCurrency.get(curr),
                    creditCountByCurrency.get(curr),
                    balanced
            ));
        }

        boolean overallBalanced = allCurrenciesBalanced
                && (totalApproved == approvedWithCompleteEntries)
                && (unapprovedWithEntries == 0)
                && anomalies.isEmpty();

        return new ReconciliationReportResponse(
                Instant.now(),
                overallBalanced,
                totalApproved,
                approvedWithCompleteEntries,
                unapprovedWithEntries,
                summaries,
                anomalies
        );
    }

    @Transactional(readOnly = true)
    public OverviewMetricsResponse getOverviewMetrics(AuthenticatedUser actor) {
        List<WalletResponse> wallets = getWalletsForUser(actor.userId());
        Map<String, Long> breakdown = new LinkedHashMap<>();
        long total = 0;
        for (PaymentStatus status : PaymentStatus.values()) {
            long c = paymentRepository.countByStatus(status);
            breakdown.put(status.name(), c);
            total += c;
        }

        Page<PaymentTransactionResponse> recentPage = listTransactions(
                actor, null, null, null, org.springframework.data.domain.PageRequest.of(0, 8,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));

        List<PaymentTransactionResponse> alerts = recentPage.getContent().stream()
                .filter(t -> t.status() == PaymentStatus.FLAGGED_FRAUD || t.status() == PaymentStatus.REVIEW_REQUIRED)
                .toList();

        return new OverviewMetricsResponse(
                wallets,
                total,
                breakdown,
                recentPage.getContent(),
                alerts
        );
    }

    public void enforceTransactionReadAuthorization(AuthenticatedUser actor, PaymentTransaction tx) {
        if (actor.role() == Role.RISK_ANALYST || actor.role() == Role.ADMIN) {
            return;
        }
        List<Wallet> userWallets = walletRepository.findByUserIdOrderByCurrencyAsc(actor.userId());
        boolean ownsWallet = userWallets.stream()
                .anyMatch(w -> w.getId().equals(tx.getSenderWalletId()) || w.getId().equals(tx.getRecipientWalletId()));
        if (!ownsWallet) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "You are not authorized to view this payment transaction.");
        }
    }

    private Wallet resolveRecipientWallet(String recipientIdentifier, String currency) {
        String trimmed = recipientIdentifier.trim();
        Optional<Wallet> byAccountNumber = walletRepository.findByAccountNumber(trimmed.toUpperCase());
        if (byAccountNumber.isPresent()) {
            Wallet w = byAccountNumber.get();
            if (!w.getCurrency().equalsIgnoreCase(currency)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH",
                        "Recipient wallet " + w.getAccountNumber() + " is denominated in " + w.getCurrency() + ", not " + currency + ".");
            }
            return w;
        }

        UserAccount recipientUser = userRepository.findByEmailIgnoreCase(trimmed.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND",
                        "No recipient user or wallet found matching '" + trimmed + "'."));

        return walletRepository.findByUserIdAndCurrencyIgnoreCase(recipientUser.getId(), currency)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_WALLET_NOT_FOUND",
                        "Recipient user does not have a " + currency + " wallet."));
    }

    private String computeRequestPayloadHash(UUID recipientWalletId,
                                             BigDecimal amount,
                                             String currency,
                                             ProcessPaymentRequest req) {
        String canonical = String.format("recipient=%s|amount=%s|curr=%s|merchant=%s|mcc=%s|src=%s|dst=%s",
                recipientWalletId,
                amount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                currency.toUpperCase(),
                req.merchantName().trim().toLowerCase(),
                req.merchantCategory().trim().toUpperCase(),
                req.sourceCountry().trim().toUpperCase(),
                req.destinationCountry().trim().toUpperCase());
        return HashUtils.sha256Hex(canonical);
    }

    private PaymentTransactionResponse verifyAndBuildIdempotentReplay(PaymentTransaction existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_PAYLOAD_MISMATCH",
                    "Idempotency-Key was already used with different payment parameters (recipient, amount, currency, or merchant).");
        }
        List<LedgerEntry> entries = ledgerRepository.findByTransactionIdOrderByEntryTypeAsc(existing.getId());
        return toTransactionResponse(existing, entries, true);
    }

    public PaymentTransactionResponse toTransactionResponse(PaymentTransaction tx,
                                                            List<LedgerEntry> entries,
                                                            boolean idempotentReplay) {
        double[] vector = readJson(tx.getFeatureVectorJson(), new TypeReference<double[]>() {}, new double[8]);
        List<ExplainableRiskFactor> factors = readJson(tx.getRiskFactorsJson(),
                new TypeReference<List<ExplainableRiskFactor>>() {}, List.of());
        List<SimilarCaseMatch> cases = readJson(tx.getMatchedCasesJson(),
                new TypeReference<List<SimilarCaseMatch>>() {}, List.of());

        List<LedgerEntryResponse> entryDtos = entries.stream()
                .map(this::toLedgerEntryResponse)
                .toList();

        return new PaymentTransactionResponse(
                tx.getId(),
                tx.getIdempotencyKey(),
                idempotentReplay,
                tx.getSenderWalletId(),
                tx.getRecipientWalletId(),
                tx.getSenderEmail(),
                tx.getRecipientEmail(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getMerchantName(),
                tx.getMerchantCategory(),
                tx.getSourceCountry(),
                tx.getDestinationCountry(),
                tx.getIpDistanceKm(),
                tx.getDeviceTrustScore(),
                tx.getAccountAgeDays(),
                tx.getVelocityCount(),
                tx.getStatus(),
                tx.getRiskScore(),
                vector,
                factors,
                cases,
                tx.getScoringSource(),
                FraudVectorEngine.CALIBRATION_DISCLAIMER,
                tx.getAnalystDecision(),
                tx.getAnalystNotes(),
                tx.getReviewedBy(),
                tx.getReviewedAt(),
                tx.getProcessingLatencyMs(),
                tx.getCorrelationId(),
                entryDtos,
                tx.getCreatedAt(),
                tx.getUpdatedAt()
        );
    }

    private LedgerEntryResponse toLedgerEntryResponse(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(),
                e.getTransactionId(),
                e.getWalletId(),
                e.getAccountNumber(),
                e.getEntryType(),
                e.getAmount(),
                e.getCurrency(),
                e.getBalanceAfter(),
                e.getDescription(),
                e.getCreatedAt()
        );
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private <T> T readJson(String json, TypeReference<T> typeRef, T defaultVal) {
        if (json == null || json.isBlank()) {
            return defaultVal;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
