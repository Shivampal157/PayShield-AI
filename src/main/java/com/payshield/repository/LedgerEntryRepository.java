package com.payshield.repository;

import com.payshield.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {
    List<LedgerEntry> findByTransactionIdOrderByEntryTypeAsc(UUID transactionId);
    List<LedgerEntry> findByWalletIdOrderByCreatedAtAsc(UUID walletId);
    List<LedgerEntry> findByWalletIdOrderByWalletSequenceAsc(UUID walletId);
    Optional<LedgerEntry> findTopByWalletIdOrderByWalletSequenceDesc(UUID walletId);
    Page<LedgerEntry> findByWalletIdIn(List<UUID> walletIds, Pageable pageable);
}
