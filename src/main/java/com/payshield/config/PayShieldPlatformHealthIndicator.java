package com.payshield.config;

import com.payshield.domain.OutboxStatus;
import com.payshield.service.FraudVectorEngine;
import com.payshield.service.OutboxPublisherService;
import com.payshield.service.RagDisputeCopilotService;
import com.payshield.service.RedisRiskEngine;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Boot Actuator HealthIndicator ("payshieldPlatform") surfacing
 * ledger integrity mode, degraded risk-engine fallbacks, and Kafka outbox DLT state.
 */
@Component("payshieldPlatform")
public class PayShieldPlatformHealthIndicator implements HealthIndicator {

    private final RedisRiskEngine redisRiskEngine;
    private final FraudVectorEngine fraudVectorEngine;
    private final OutboxPublisherService outboxPublisherService;

    public PayShieldPlatformHealthIndicator(RedisRiskEngine redisRiskEngine,
                                            FraudVectorEngine fraudVectorEngine,
                                            OutboxPublisherService outboxPublisherService) {
        this.redisRiskEngine = redisRiskEngine;
        this.fraudVectorEngine = fraudVectorEngine;
        this.outboxPublisherService = outboxPublisherService;
    }

    @Override
    public Health health() {
        long pendingOutbox = outboxPublisherService.countByStatus(OutboxStatus.PENDING);
        long deadLetterOutbox = outboxPublisherService.countByStatus(OutboxStatus.DEAD_LETTER);
        boolean redisLive = redisRiskEngine.isRedisHealthy();
        boolean openSearchLive = fraudVectorEngine.isOpenSearchHealthy();

        return Health.up()
                .withDetail("ledgerJournalMode", "DOUBLE_ENTRY_NUMERIC_19_4_SHA256_CHAIN")
                .withDetail("velocityEngine", redisLive ? "REDIS_LUA_ZSET" : "LOCAL_CONCURRENT_FALLBACK")
                .withDetail("vectorScoringEngine", openSearchLive ? "OPENSEARCH_KNN_COSINESIMIL" : "DETERMINISTIC_HEURISTIC_FALLBACK")
                .withDetail("outboxPendingEvents", pendingOutbox)
                .withDetail("outboxDeadLetterEvents", deadLetterOutbox)
                .withDetail("outboxAttentionRequired", deadLetterOutbox > 0)
                .withDetail("policyCorpusVersion", RagDisputeCopilotService.POLICY_CORPUS_VERSION)
                .build();
    }
}
