package com.payshield.repository;

import com.payshield.domain.PaymentStatus;
import com.payshield.domain.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID>, JpaSpecificationExecutor<PaymentTransaction> {
    Optional<PaymentTransaction> findBySenderWalletIdAndIdempotencyKey(UUID senderWalletId, String idempotencyKey);
    Page<PaymentTransaction> findByStatusIn(List<PaymentStatus> statuses, Pageable pageable);
    long countByStatus(PaymentStatus status);
    List<PaymentTransaction> findTop20ByOrderByCreatedAtDesc();
}
