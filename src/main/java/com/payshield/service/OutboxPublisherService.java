package com.payshield.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.AppProperties;
import com.payshield.config.KafkaConfig;
import com.payshield.domain.OutboxEvent;
import com.payshield.domain.OutboxStatus;
import com.payshield.dto.TransactionEventPayload;
import com.payshield.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox Relay Publisher.
 *
 * Delivery Guarantees:
 * - OutboxEvent records are persisted inside the same ACID database transaction as the payment/ledger change.
 * - Relay polls PENDING events and publishes to Kafka topic "transaction-events" with at-least-once semantics.
 * - Failed deliveries retry up to maxAttempts (default 3) with exponential backoff (2^attempt seconds).
 * - Exhausted events transition to DEAD_LETTER status and attempt publication to "transaction-events.DLT".
 * - Downstream consumers enforce idempotency via ProcessedKafkaEvent (event_id PK deduplication).
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionEventConsumer localConsumerLoopback;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    private volatile boolean simulateBrokerFailureForTest = false;
    private volatile boolean enableLocalLoopbackWhenBrokerOffline = true;

    public OutboxPublisherService(OutboxEventRepository outboxRepository,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  TransactionEventConsumer localConsumerLoopback,
                                  AppProperties properties,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.localConsumerLoopback = localConsumerLoopback;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.publishedCounter = Counter.builder("payshield.outbox.published")
                .description("Outbox events successfully published")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("payshield.outbox.retries")
                .description("Outbox event publish retry attempts")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("payshield.outbox.dead_letter")
                .description("Outbox events moved to DEAD_LETTER after exhausting retries")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueueTransactionEvent(TransactionEventPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = new OutboxEvent(
                    payload.eventId() != null ? payload.eventId() : UUID.randomUUID(),
                    "PAYMENT_TRANSACTION",
                    payload.transactionId().toString(),
                    payload.eventType(),
                    KafkaConfig.TRANSACTION_EVENTS_TOPIC,
                    json,
                    payload.correlationId()
            );
            return outboxRepository.save(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize TransactionEventPayload for outbox", ex);
        }
    }

    @Scheduled(fixedDelayString = "${payshield.outbox.poll-interval-ms:3000}")
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEvent> pending = outboxRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus.PENDING, Instant.now());
        int processedCount = 0;
        for (OutboxEvent event : pending) {
            processSingleOutboxEvent(event);
            processedCount++;
        }
        return processedCount;
    }

    @Transactional
    public void processSingleOutboxEvent(OutboxEvent event) {
        int currentAttempt = event.getAttempts() + 1;
        event.setAttempts(currentAttempt);
        try {
            if (simulateBrokerFailureForTest) {
                throw new IllegalStateException("Simulated Kafka broker failure");
            }
            publishToKafkaOrLoopback(event);
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
            outboxRepository.save(event);
            publishedCounter.increment();
        } catch (Exception ex) {
            int maxAttempts = properties.getOutbox().getMaxAttempts();
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            if (errorMsg.length() > 900) {
                errorMsg = errorMsg.substring(0, 900);
            }
            event.setLastError(errorMsg);

            if (currentAttempt >= maxAttempts) {
                event.setStatus(OutboxStatus.DEAD_LETTER);
                deadLetterCounter.increment();
                log.warn("Outbox event id={} aggregateId={} exhausted {} attempts; moved to DEAD_LETTER [correlationId={}]",
                        event.getId(), event.getAggregateId(), maxAttempts, event.getCorrelationId());
                tryPublishDeadLetterTopic(event);
            } else {
                long backoffSeconds = (long) Math.pow(2, currentAttempt);
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                retryCounter.increment();
                log.info("Outbox event id={} attempt #{}/{} failed ({}); scheduled retry in {}s",
                        event.getId(), currentAttempt, maxAttempts, errorMsg, backoffSeconds);
            }
            outboxRepository.save(event);
        }
    }

    private void publishToKafkaOrLoopback(OutboxEvent event) throws Exception {
        String envLabel = properties.getEnvironmentLabel();
        if (enableLocalLoopbackWhenBrokerOffline
                && ("test".equalsIgnoreCase(envLabel) || "local-embedded".equalsIgnoreCase(envLabel))) {
            localConsumerLoopback.processEventIdempotently(event.getPayloadJson());
            return;
        }
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getAggregateId(),
                    event.getPayloadJson()
            );
            record.headers().add("X-Correlation-Id", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
            record.headers().add("X-Schema-Version", event.getSchemaVersion().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception kafkaEx) {
            if (enableLocalLoopbackWhenBrokerOffline) {
                localConsumerLoopback.processEventIdempotently(event.getPayloadJson());
                return;
            }
            throw kafkaEx;
        }
    }

    private void tryPublishDeadLetterTopic(OutboxEvent event) {
        if (simulateBrokerFailureForTest) {
            return;
        }
        try {
            ProducerRecord<String, String> dltRecord = new ProducerRecord<>(
                    KafkaConfig.TRANSACTION_EVENTS_DLT,
                    event.getAggregateId(),
                    event.getPayloadJson()
            );
            kafkaTemplate.send(dltRecord).get(1000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    /**
     * Administrative recovery action: resets DEAD_LETTER events back to PENDING for re-processing.
     */
    @Transactional
    public int recoverDeadLetterEvents() {
        List<OutboxEvent> deadEvents = outboxRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(OutboxStatus.DEAD_LETTER, Instant.now().plusSeconds(86400));
        for (OutboxEvent e : deadEvents) {
            e.setStatus(OutboxStatus.PENDING);
            e.setAttempts(0);
            e.setNextAttemptAt(Instant.now());
            outboxRepository.save(e);
        }
        return deadEvents.size();
    }

    public long countByStatus(OutboxStatus status) {
        return outboxRepository.countByStatus(status);
    }

    public void setSimulateBrokerFailureForTest(boolean simulateBrokerFailureForTest) {
        this.simulateBrokerFailureForTest = simulateBrokerFailureForTest;
    }

    public void setEnableLocalLoopbackWhenBrokerOffline(boolean enableLocalLoopbackWhenBrokerOffline) {
        this.enableLocalLoopbackWhenBrokerOffline = enableLocalLoopbackWhenBrokerOffline;
    }
}
