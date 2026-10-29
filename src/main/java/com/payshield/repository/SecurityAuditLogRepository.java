package com.payshield.repository;

import com.payshield.domain.SecurityAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, UUID> {
    Page<SecurityAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<SecurityAuditLog> findByResourceIdOrderByCreatedAtDesc(String resourceId);
}
