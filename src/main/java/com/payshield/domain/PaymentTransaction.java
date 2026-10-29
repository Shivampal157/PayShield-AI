package com.payshield.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payment_transactions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payment_sender_idempotency", columnNames = {"sender_wallet_id", "idempotency_key"})
    }
)
public class PaymentTransaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "sender_wallet_id", nullable = false)
    private UUID senderWalletId;

    @Column(name = "recipient_wallet_id", nullable = false)
    private UUID recipientWalletId;

    @Column(name = "sender_email", nullable = false, length = 255)
    private String senderEmail;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "merchant_name", nullable = false, length = 255)
    private String merchantName;

    @Column(name = "merchant_category", nullable = false, length = 64)
    private String merchantCategory;

    @Column(name = "source_country", nullable = false, length = 8)
    private String sourceCountry;

    @Column(name = "destination_country", nullable = false, length = 8)
    private String destinationCountry;

    @Column(name = "ip_distance_km", nullable = false)
    private double ipDistanceKm;

    @Column(name = "device_trust_score", nullable = false)
    private double deviceTrustScore;

    @Column(name = "account_age_days", nullable = false)
    private int accountAgeDays;

    @Column(name = "velocity_count", nullable = false)
    private int velocityCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "risk_score", nullable = false)
    private double riskScore;

    @Column(name = "feature_vector_json", nullable = false, length = 1024)
    private String featureVectorJson;

    @Column(name = "risk_factors_json", nullable = false, length = 4000)
    private String riskFactorsJson;

    @Column(name = "matched_cases_json", nullable = false, length = 4000)
    private String matchedCasesJson;

    @Column(name = "scoring_source", nullable = false, length = 64)
    private String scoringSource;

    @Column(name = "analyst_decision", length = 64)
    private String analystDecision;

    @Column(name = "analyst_notes", length = 1024)
    private String analystNotes;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "processing_latency_ms", nullable = false)
    private long processingLatencyMs;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public PaymentTransaction() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public UUID getSenderWalletId() {
        return senderWalletId;
    }

    public void setSenderWalletId(UUID senderWalletId) {
        this.senderWalletId = senderWalletId;
    }

    public UUID getRecipientWalletId() {
        return recipientWalletId;
    }

    public void setRecipientWalletId(UUID recipientWalletId) {
        this.recipientWalletId = recipientWalletId;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount.setScale(4, RoundingMode.HALF_UP);
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public void setMerchantCategory(String merchantCategory) {
        this.merchantCategory = merchantCategory;
    }

    public String getSourceCountry() {
        return sourceCountry;
    }

    public void setSourceCountry(String sourceCountry) {
        this.sourceCountry = sourceCountry;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public void setDestinationCountry(String destinationCountry) {
        this.destinationCountry = destinationCountry;
    }

    public double getIpDistanceKm() {
        return ipDistanceKm;
    }

    public void setIpDistanceKm(double ipDistanceKm) {
        this.ipDistanceKm = ipDistanceKm;
    }

    public double getDeviceTrustScore() {
        return deviceTrustScore;
    }

    public void setDeviceTrustScore(double deviceTrustScore) {
        this.deviceTrustScore = deviceTrustScore;
    }

    public int getAccountAgeDays() {
        return accountAgeDays;
    }

    public void setAccountAgeDays(int accountAgeDays) {
        this.accountAgeDays = accountAgeDays;
    }

    public int getVelocityCount() {
        return velocityCount;
    }

    public void setVelocityCount(int velocityCount) {
        this.velocityCount = velocityCount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }

    public String getFeatureVectorJson() {
        return featureVectorJson;
    }

    public void setFeatureVectorJson(String featureVectorJson) {
        this.featureVectorJson = featureVectorJson;
    }

    public String getRiskFactorsJson() {
        return riskFactorsJson;
    }

    public void setRiskFactorsJson(String riskFactorsJson) {
        this.riskFactorsJson = riskFactorsJson;
    }

    public String getMatchedCasesJson() {
        return matchedCasesJson;
    }

    public void setMatchedCasesJson(String matchedCasesJson) {
        this.matchedCasesJson = matchedCasesJson;
    }

    public String getScoringSource() {
        return scoringSource;
    }

    public void setScoringSource(String scoringSource) {
        this.scoringSource = scoringSource;
    }

    public String getAnalystDecision() {
        return analystDecision;
    }

    public void setAnalystDecision(String analystDecision) {
        this.analystDecision = analystDecision;
    }

    public String getAnalystNotes() {
        return analystNotes;
    }

    public void setAnalystNotes(String analystNotes) {
        this.analystNotes = analystNotes;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public long getProcessingLatencyMs() {
        return processingLatencyMs;
    }

    public void setProcessingLatencyMs(long processingLatencyMs) {
        this.processingLatencyMs = processingLatencyMs;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
