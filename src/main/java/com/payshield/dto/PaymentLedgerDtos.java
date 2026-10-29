package com.payshield.dto;

import com.payshield.domain.LedgerEntryType;
import com.payshield.domain.PaymentStatus;
import com.payshield.service.FraudVectorEngine.ExplainableRiskFactor;
import com.payshield.service.FraudVectorEngine.SimilarCaseMatch;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PaymentLedgerDtos {

    private PaymentLedgerDtos() {
    }

    public record ProcessPaymentRequest(
            @NotBlank(message = "Recipient email or account number is required")
            String recipientIdentifier,

            @NotNull(message = "Amount is required")
            @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @DecimalMax(value = "1000000.00", message = "Amount exceeds maximum single transfer limit")
            BigDecimal amount,

            @NotBlank(message = "Currency is required")
            @Pattern(regexp = "^(USD|EUR|GBP|usd|eur|gbp)$", message = "Currency must be USD, EUR, or GBP")
            String currency,

            @NotBlank(message = "Merchant or counterfactual description is required")
            @Size(max = 180, message = "Merchant name cannot exceed 180 characters")
            String merchantName,

            @NotBlank(message = "Merchant category is required")
            String merchantCategory,

            @NotBlank(message = "Source country ISO code is required")
            @Size(min = 2, max = 3, message = "Source country must be a 2 or 3 letter ISO code")
            String sourceCountry,

            @NotBlank(message = "Destination country ISO code is required")
            @Size(min = 2, max = 3, message = "Destination country must be a 2 or 3 letter ISO code")
            String destinationCountry,

            @Min(value = 0, message = "IP distance cannot be negative")
            @Max(value = 25000, message = "IP distance cannot exceed 25,000 km")
            double ipDistanceKm,

            @DecimalMin(value = "0.0", message = "Device trust score must be >= 0.0")
            @DecimalMax(value = "1.0", message = "Device trust score must be <= 1.0")
            Double deviceTrustScore,

            @Min(value = 0, message = "Account age days cannot be negative")
            Integer accountAgeDays
    ) {
    }

    public record AnalystReviewRequest(
            @NotBlank(message = "Decision is required (APPROVE or DECLINE)")
            @Pattern(regexp = "^(APPROVE|DECLINE)$", message = "Decision must be APPROVE or DECLINE")
            String decision,

            @NotBlank(message = "Analyst review notes are required")
            @Size(min = 4, max = 800, message = "Notes must be between 4 and 800 characters")
            String notes
    ) {
    }

    public record WalletResponse(
            UUID id,
            UUID userId,
            String ownerEmail,
            String accountNumber,
            String currency,
            BigDecimal balance,
            String status,
            Instant updatedAt
    ) {
    }

    public record LedgerEntryResponse(
            UUID id,
            UUID transactionId,
            UUID walletId,
            String accountNumber,
            LedgerEntryType entryType,
            BigDecimal amount,
            String currency,
            BigDecimal balanceAfter,
            String description,
            Instant createdAt
    ) {
    }

    public record PaymentTransactionResponse(
            UUID id,
            String idempotencyKey,
            boolean idempotentReplay,
            UUID senderWalletId,
            UUID recipientWalletId,
            String senderEmail,
            String recipientEmail,
            BigDecimal amount,
            String currency,
            String merchantName,
            String merchantCategory,
            String sourceCountry,
            String destinationCountry,
            double ipDistanceKm,
            double deviceTrustScore,
            int accountAgeDays,
            int velocityCount,
            PaymentStatus status,
            double riskScore,
            double[] featureVector,
            List<ExplainableRiskFactor> riskFactors,
            List<SimilarCaseMatch> matchedCases,
            String scoringSource,
            String methodologyDisclaimer,
            String analystDecision,
            String analystNotes,
            String reviewedBy,
            Instant reviewedAt,
            long processingLatencyMs,
            String correlationId,
            List<LedgerEntryResponse> ledgerEntries,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CurrencyReconciliationSummary(
            String currency,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            BigDecimal netDifference,
            long debitEntryCount,
            long creditEntryCount,
            boolean zeroSumBalanced
    ) {
    }

    public record ReconciliationReportResponse(
            Instant generatedAt,
            boolean overallBalanced,
            long totalApprovedTransactions,
            long approvedTransactionsWithCompleteDoubleEntry,
            long unapprovedTransactionsWithUnexpectedEntries,
            List<CurrencyReconciliationSummary> currencySummaries,
            List<String> anomalies
    ) {
    }

    public record VelocitySimulationResponse(
            int totalRequestsExecuted,
            int approvedCount,
            int flaggedOrBlockedCount,
            int maxWindowThreshold,
            int windowSeconds,
            String engineMode,
            List<PaymentTransactionResponse> transactions
    ) {
    }

    public record OverviewMetricsResponse(
            List<WalletResponse> wallets,
            long totalTransactions,
            Map<String, Long> statusBreakdown,
            List<PaymentTransactionResponse> recentTransactions,
            List<PaymentTransactionResponse> riskAlerts
    ) {
    }
}
