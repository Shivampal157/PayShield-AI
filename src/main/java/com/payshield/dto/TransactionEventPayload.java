package com.payshield.dto;

import com.payshield.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionEventPayload(
        UUID eventId,
        String schemaVersion,
        String eventType,
        UUID transactionId,
        UUID senderWalletId,
        UUID recipientWalletId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        double riskScore,
        String correlationId,
        Instant occurredAt
) {
    public static final String CURRENT_SCHEMA_VERSION = "1.0";
}
