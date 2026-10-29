package com.payshield.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safe local-development email adapter for password reset workflows.
 * Never logs raw tokens in production; stores local outbox notifications in-memory
 * for deterministic verification in tests and local development without external SMTP credentials.
 */
@Service
public class LocalDevEmailAdapter {

    private static final Logger log = LoggerFactory.getLogger(LocalDevEmailAdapter.class);

    public record SimulatedEmail(String recipientEmail, String subject, String resetToken, Instant sentAt) {
    }

    private final Map<String, SimulatedEmail> lastEmailByRecipient = new ConcurrentHashMap<>();

    public void sendPasswordResetEmail(String recipientEmail, String resetToken, Instant expiresAt) {
        String normalized = recipientEmail.trim().toLowerCase();
        SimulatedEmail email = new SimulatedEmail(
                normalized,
                "PayShield AI — Password Reset Instructions",
                resetToken,
                Instant.now()
        );
        lastEmailByRecipient.put(normalized, email);
        log.info("LocalDevEmailAdapter queued password reset notification for recipient={} (expiresAt={})",
                normalized, expiresAt);
    }

    public Optional<SimulatedEmail> getLastEmailForRecipient(String recipientEmail) {
        return Optional.ofNullable(lastEmailByRecipient.get(recipientEmail.trim().toLowerCase()));
    }

    public void clear() {
        lastEmailByRecipient.clear();
    }
}
