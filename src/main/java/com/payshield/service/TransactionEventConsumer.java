package com.payshield.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.KafkaConfig;
import com.payshield.domain.ProcessedKafkaEvent;
import com.payshield.dto.TransactionEventPayload;
import com.payshield.repository.ProcessedKafkaEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);
    private static final String CONSUMER_GROUP = "payshield-ledger-group";

    private final ProcessedKafkaEventRepository processedRepository;
    private final ObjectMapper objectMapper;
    private final Counter consumedCounter;
    private final Counter duplicateIgnoredCounter;

    public TransactionEventConsumer(ProcessedKafkaEventRepository processedRepository,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.processedRepository = processedRepository;
        this.objectMapper = objectMapper;
        this.consumedCounter = Counter.builder("payshield.kafka.events.consumed")
                .description("Unique transaction events processed by consumer")
                .register(meterRegistry);
        this.duplicateIgnoredCounter = Counter.builder("payshield.kafka.events.duplicates")
                .description("Duplicate transaction events safely ignored by idempotent consumer")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaConfig.TRANSACTION_EVENTS_TOPIC,
            groupId = CONSUMER_GROUP
    )
    @Transactional
    public void onMessage(String payloadJson) throws Exception {
        processEventIdempotently(payloadJson);
    }

    /**
     * Processes a versioned transaction-events payload idempotently.
     * Returns true if newly processed, false if duplicate eventId was already recorded.
     */
    @Transactional
    public boolean processEventIdempotently(String payloadJson) throws Exception {
        TransactionEventPayload payload = objectMapper.readValue(payloadJson, TransactionEventPayload.class);
        UUID eventId = payload.eventId();
        if (eventId == null) {
            throw new IllegalArgumentException("Event payload missing mandatory eventId");
        }

        if (processedRepository.existsById(eventId)) {
            duplicateIgnoredCounter.increment();
            log.info("Idempotent consumer ignored duplicate eventId={} transactionId={} [correlationId={}]",
                    eventId, payload.transactionId(), payload.correlationId());
            return false;
        }

        String summary = String.format("tx=%s status=%s amount=%s %s risk=%.4f schema=%s",
                payload.transactionId(), payload.status(), payload.amount(),
                payload.currency(), payload.riskScore(), payload.schemaVersion());

        ProcessedKafkaEvent processed = new ProcessedKafkaEvent(
                eventId,
                KafkaConfig.TRANSACTION_EVENTS_TOPIC,
                CONSUMER_GROUP,
                payload.correlationId(),
                summary
        );
        processedRepository.save(processed);
        consumedCounter.increment();

        log.info("Processed transaction eventId={} type={} txId={} status={} [correlationId={}]",
                eventId, payload.eventType(), payload.transactionId(), payload.status(), payload.correlationId());
        return true;
    }
}
