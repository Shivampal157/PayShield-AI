package com.payshield.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_kafka_events")
public class ProcessedKafkaEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "consumer_group", nullable = false, length = 128)
    private String consumerGroup;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "payload_summary", length = 512)
    private String payloadSummary;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedKafkaEvent() {
    }

    public ProcessedKafkaEvent(UUID eventId, String topic, String consumerGroup, String correlationId, String payloadSummary) {
        this.eventId = eventId;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.correlationId = correlationId;
        this.payloadSummary = payloadSummary;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayloadSummary() {
        return payloadSummary;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
