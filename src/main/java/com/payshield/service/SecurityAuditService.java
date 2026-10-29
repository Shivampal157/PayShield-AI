package com.payshield.service;

import com.payshield.config.CorrelationIdFilter;
import com.payshield.domain.SecurityAuditLog;
import com.payshield.repository.SecurityAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final SecurityAuditLogRepository repository;

    public SecurityAuditService(SecurityAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvent(UUID userId,
                            String actorEmail,
                            String eventType,
                            String resourceType,
                            String resourceId,
                            String ipAddress,
                            String details) {
        String correlationId = CorrelationIdFilter.currentCorrelationId();
        SecurityAuditLog entry = new SecurityAuditLog(
                UUID.randomUUID(),
                userId,
                actorEmail != null ? actorEmail : "anonymous",
                eventType,
                resourceType,
                resourceId,
                ipAddress,
                details,
                correlationId
        );
        repository.save(entry);
        log.info("AUDIT [event={}] [actor={}] [resource={}:{}] [correlationId={}] - {}",
                eventType, actorEmail, resourceType, resourceId, correlationId, details);
    }

    @Transactional(readOnly = true)
    public Page<SecurityAuditLog> listAuditLogs(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public List<SecurityAuditLog> listByResourceId(String resourceId) {
        return repository.findByResourceIdOrderByCreatedAtDesc(resourceId);
    }
}
