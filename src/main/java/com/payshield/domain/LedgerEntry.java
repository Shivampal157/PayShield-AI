package com.payshield.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(
    name = "ledger_entries",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_ledger_tx_entry_type", columnNames = {"transaction_id", "entry_type"})
    }
)
public class LedgerEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Column(name = "account_number", nullable = false, updatable = false, length = 64)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 16)
    private LedgerEntryType entryType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 10)
    private String currency;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false, length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry() {
    }

    public LedgerEntry(UUID id, UUID transactionId, UUID walletId, String accountNumber,
                       LedgerEntryType entryType, BigDecimal amount, String currency,
                       BigDecimal balanceAfter, String description) {
        this.id = id;
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.accountNumber = accountNumber;
        this.entryType = entryType;
        this.amount = amount.setScale(4, RoundingMode.HALF_UP);
        this.currency = currency;
        this.balanceAfter = balanceAfter.setScale(4, RoundingMode.HALF_UP);
        this.description = description;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
