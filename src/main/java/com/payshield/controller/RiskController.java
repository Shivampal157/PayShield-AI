package com.payshield.controller;

import com.payshield.config.AppProperties;
import com.payshield.config.KafkaConfig;
import com.payshield.domain.OutboxStatus;
import com.payshield.domain.PaymentStatus;
import com.payshield.domain.SecurityAuditLog;
import com.payshield.dto.PaymentLedgerDtos.AnalystReviewRequest;
import com.payshield.dto.PaymentLedgerDtos.PaymentTransactionResponse;
import com.payshield.dto.PaymentLedgerDtos.VelocitySimulationResponse;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Risk Operations & Observability", description = "Suspicious transaction queue, analyst review, velocity simulation, and risk telemetry")
public class RiskController {

    private final PaymentLedgerService paymentLedgerService;
    private final RedisRiskEngine redisRiskEngine;
    private final FraudVectorEngine fraudVectorEngine;
    private final OutboxPublisherService outboxPublisherService;
    private final SecurityAuditService securityAuditService;
    private final AppProperties properties;

    public RiskController(PaymentLedgerService paymentLedgerService,
                          RedisRiskEngine redisRiskEngine,
                          FraudVectorEngine fraudVectorEngine,
                          OutboxPublisherService outboxPublisherService,
                          SecurityAuditService securityAuditService,
                          AppProperties properties) {
        this.paymentLedgerService = paymentLedgerService;
        this.redisRiskEngine = redisRiskEngine;
        this.fraudVectorEngine = fraudVectorEngine;
        this.outboxPublisherService = outboxPublisherService;
        this.securityAuditService = securityAuditService;
        this.properties = properties;
    }

    @GetMapping("/risk/transactions")
    @Operation(summary = "List suspicious, flagged, or review-required transactions for Risk Operations")
    public ResponseEntity<Page<PaymentTransactionResponse>> getRiskTransactions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return ResponseEntity.ok(paymentLedgerService.listSuspiciousTransactions(status, pageable));
    }

    @PostMapping("/risk/transactions/{id}/review")
    @PreAuthorize("hasAnyRole('RISK_ANALYST', 'ADMIN')")
    @Operation(summary = "Execute Risk Analyst or Admin review decision (APPROVE or DECLINE) on a quarantined transaction")
    public ResponseEntity<PaymentTransactionResponse> reviewTransaction(
            @AuthenticationPrincipal AuthenticatedUser analyst,
            @PathVariable UUID id,
            @Valid @RequestBody AnalystReviewRequest request,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(paymentLedgerService.reviewTransaction(
                analyst, id, request, AuthController.resolveClientIp(httpRequest)));
    }

    @PostMapping("/risk/simulate-velocity")
    @Operation(summary = "Trigger a 7-attempt rapid payment burst to test sliding-window velocity limiting (>5 attempts/60s)")
    public ResponseEntity<VelocitySimulationResponse> simulateVelocity(
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(paymentLedgerService.simulateVelocityAttack(
                user, AuthController.resolveClientIp(httpRequest)));
    }

    @GetMapping("/risk/metrics")
    @Operation(summary = "Get real-time Redis cache metrics, OpenSearch k-NN status, Kafka outbox counters, and risk configuration")
    public ResponseEntity<Map<String, Object>> getRiskMetrics() {
        boolean redisUp = redisRiskEngine.isRedisHealthy();
        boolean openSearchUp = fraudVectorEngine.isOpenSearchHealthy();
        long hits = redisRiskEngine.getRagCacheHits();
        long misses = redisRiskEngine.getRagCacheMisses();
        long totalLookups = hits + misses;
        double hitRate = totalLookups > 0 ? Math.round((hits * 10000.0) / totalLookups) / 100.0 : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("environment", properties.getEnvironmentLabel());
        body.put("redis", Map.of(
                "healthy", redisUp,
                "velocityEngineMode", redisUp ? "REDIS_LUA_ZSET" : "LOCAL_CONCURRENT_FALLBACK",
                "velocityMaxAttempts", properties.getRisk().getVelocityMaxAttempts(),
                "velocityWindowSeconds", properties.getRisk().getVelocityWindowSeconds(),
                "ragCacheTtlHours", properties.getRisk().getRagCacheTtlHours(),
                "ragCacheHits", hits,
                "ragCacheMisses", misses,
                "ragCacheHitRatePercent", hitRate
        ));
        body.put("openSearch", Map.of(
                "healthy", openSearchUp,
                "indexName", properties.getOpensearch().getIndexName(),
                "schemaVersion", FraudVectorEngine.INDEX_SCHEMA_VERSION,
                "vectorDimension", FraudVectorEngine.VECTOR_DIMENSION,
                "featureNames", FraudVectorEngine.FEATURE_NAMES,
                "scoringMode", openSearchUp ? "OPENSEARCH_KNN_COSINESIMIL" : "DETERMINISTIC_HEURISTIC_FALLBACK",
                "flagThreshold", properties.getRisk().getFlagThreshold(),
                "declineThreshold", properties.getRisk().getDeclineThreshold(),
                "seededSyntheticPatterns", fraudVectorEngine.getSyntheticPatterns(),
                "calibrationDisclaimer", FraudVectorEngine.CALIBRATION_DISCLAIMER
        ));
        body.put("kafkaOutbox", Map.of(
                "topic", KafkaConfig.TRANSACTION_EVENTS_TOPIC,
                "deadLetterTopic", KafkaConfig.TRANSACTION_EVENTS_DLT,
                "deliveryGuarantee", "AT_LEAST_ONCE_OUTBOX_WITH_IDEMPOTENT_CONSUMER",
                "pendingEvents", outboxPublisherService.countByStatus(OutboxStatus.PENDING),
                "publishedEvents", outboxPublisherService.countByStatus(OutboxStatus.PUBLISHED),
                "deadLetterEvents", outboxPublisherService.countByStatus(OutboxStatus.DEAD_LETTER),
                "maxRetryAttempts", properties.getOutbox().getMaxAttempts()
        ));
        body.put("aiCopilot", Map.of(
                "configuredProvider", properties.getAi().getProvider(),
                "apiKeyConfigured", properties.getAi().getApiKey() != null && !properties.getAi().getApiKey().isBlank(),
                "model", properties.getAi().getModel(),
                "policyCorpusVersion", RagDisputeCopilotService.POLICY_CORPUS_VERSION
        ));

        return ResponseEntity.ok(body);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('RISK_ANALYST', 'ADMIN')")
    @Operation(summary = "List security and operational audit events (Risk Analyst / Admin only)")
    public ResponseEntity<Page<SecurityAuditLog>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(securityAuditService.listAuditLogs(PageRequest.of(Math.max(0, page), Math.min(100, size))));
    }
}
