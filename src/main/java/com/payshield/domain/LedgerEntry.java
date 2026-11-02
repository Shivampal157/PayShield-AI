package com.payshield.domain;

import com.payshield.util.HashUtils;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Immutable
@Table(
    name = "ledger_entries",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_ledger_tx_entry_type", columnNames = {"transaction_id", "entry_type"}),
        @UniqueConstraint(name = "uq_ledger_wallet_sequence", columnNames = {"wallet_id", "wallet_sequence"})
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

    @Column(name = "wallet_sequence", nullable = false, updatable = false)
    private long walletSequence;

    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    @Column(name = "entry_hash", nullable = false, updatable = false, length = 64)
    private String entryHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry() {
    }

    public LedgerEntry(UUID id,
                       UUID transactionId,
                       UUID walletId,
                       String accountNumber,
                       LedgerEntryType entryType,
                       BigDecimal amount,
                       String currency,
                       BigDecimal balanceAfter,
                       String description,
                       long walletSequence,
                       String previousHash) {
        this.id = id;
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.accountNumber = accountNumber;
        this.entryType = entryType;
        this.amount = amount.setScale(4, RoundingMode.HALF_UP);
        this.currency = currency.toUpperCase();
        this.balanceAfter = balanceAfter.setScale(4, RoundingMode.HALF_UP);
        this.description = description;
        this.walletSequence = walletSequence;
        this.previousHash = previousHash;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        this.entryHash = computeEntryHash(
                this.walletSequence,
                this.previousHash,
                this.transactionId,
                this.walletId,
                this.entryType,
                this.amount,
                this.currency,
                this.balanceAfter,
                this.createdAt
        );
    }

    public static String genesisHashForWallet(UUID walletId) {
        return HashUtils.sha256Hex("PAYSHIELD_LEDGER_GENESIS_V1|" + walletId);
    }

    public static String computeEntryHash(long walletSequence,
                                          String previousHash,
                                          UUID transactionId,
                                          UUID walletId,
                                          LedgerEntryType entryType,
                                          BigDecimal amount,
                                          String currency,
                                          BigDecimal balanceAfter,
                                          Instant createdAt) {
        String canonical = String.format(
                "v1|seq=%d|prev=%s|tx=%s|wallet=%s|type=%s|amt=%s|curr=%s|balAfter=%s|ts=%d",
                walletSequence,
                previousHash,
                transactionId,
                walletId,
                entryType.name(),
                amount.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                currency.toUpperCase(),
                balanceAfter.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                createdAt.truncatedTo(ChronoUnit.MILLIS).toEpochMilli()
        );
        return HashUtils.sha256Hex(canonical);
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

    public long getWalletSequence() {
        return walletSequence;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
