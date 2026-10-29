package com.payshield;

import com.payshield.domain.OutboxEvent;
import com.payshield.domain.OutboxStatus;
import com.payshield.domain.PaymentStatus;
import com.payshield.dto.TransactionEventPayload;
import com.payshield.repository.OutboxEventRepository;
import com.payshield.repository.ProcessedKafkaEventRepository;
import com.payshield.service.OutboxPublisherService;
import com.payshield.service.TransactionEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class KafkaOutboxAndConsumerTest {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private TransactionEventConsumer eventConsumer;

    @Autowired
    private ProcessedKafkaEventRepository processedRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetState() {
        outboxPublisherService.setSimulateBrokerFailureForTest(false);
        outboxPublisherService.setEnableLocalLoopbackWhenBrokerOffline(true);
    }

    @Test
    @DisplayName("Consumer processes versioned event once and ignores duplicate eventId deliveries")
    void consumerIdempotencyPreventsDuplicateProcessing() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String json = String.format("""
                {
                  "eventId": "%s",
                  "schemaVersion": "1.0",
                  "eventType": "PAYMENT_APPROVED",
                  "transactionId": "%s",
                  "senderWalletId": "%s",
                  "recipientWalletId": "%s",
                  "amount": 120.0000,
                  "currency": "USD",
                  "status": "APPROVED",
                  "riskScore": 0.12,
                  "correlationId": "ps-test-corr-1",
                  "occurredAt": "%s"
                }
                """, eventId, txId, UUID.randomUUID(), UUID.randomUUID(), Instant.now().toString());

        boolean first = eventConsumer.processEventIdempotently(json);
        boolean secondDuplicate = eventConsumer.processEventIdempotently(json);

        assertThat(first).isTrue();
        assertThat(secondDuplicate).isFalse();
        assertThat(processedRepository.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("Outbox retries 3 times with exponential backoff, moves to DEAD_LETTER, and supports recovery")
    void outboxRetriesDeadLettersAndRecovers() {
        UUID eventId = UUID.randomUUID();
        TransactionEventPayload payload = new TransactionEventPayload(
                eventId,
                TransactionEventPayload.CURRENT_SCHEMA_VERSION,
                "PAYMENT_APPROVED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("75.0000"),
                "USD",
                PaymentStatus.APPROVED,
                0.15,
                "ps-outbox-test",
                Instant.now()
        );

        OutboxEvent saved = transactionTemplate.execute(status -> outboxPublisherService.enqueueTransactionEvent(payload));
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Simulate persistent Kafka broker failure across 3 attempts
        outboxPublisherService.setSimulateBrokerFailureForTest(true);

        // Attempt 1
        outboxPublisherService.processSingleOutboxEvent(outboxRepository.findById(eventId).orElseThrow());
        OutboxEvent after1 = outboxRepository.findById(eventId).orElseThrow();
        assertThat(after1.getAttempts()).isEqualTo(1);
        assertThat(after1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after1.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));

        // Attempt 2
        outboxPublisherService.processSingleOutboxEvent(after1);
        OutboxEvent after2 = outboxRepository.findById(eventId).orElseThrow();
        assertThat(after2.getAttempts()).isEqualTo(2);
        assertThat(after2.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Attempt 3 (Exhausts maxAttempts = 3 -> moves to DEAD_LETTER)
        outboxPublisherService.processSingleOutboxEvent(after2);
        OutboxEvent after3 = outboxRepository.findById(eventId).orElseThrow();
        assertThat(after3.getAttempts()).isEqualTo(3);
        assertThat(after3.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(after3.getLastError()).contains("Simulated Kafka broker failure");

        // Recover DEAD_LETTER events and verify successful publication when broker recovers
        outboxPublisherService.setSimulateBrokerFailureForTest(false);
        int recovered = outboxPublisherService.recoverDeadLetterEvents();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        OutboxEvent resetEvent = outboxRepository.findById(eventId).orElseThrow();
        assertThat(resetEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        outboxPublisherService.processSingleOutboxEvent(resetEvent);
        OutboxEvent published = outboxRepository.findById(eventId).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(processedRepository.existsById(eventId)).isTrue();
    }
}
