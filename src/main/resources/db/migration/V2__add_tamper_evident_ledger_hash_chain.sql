-- ============================================================================
-- PayShield AI — Flyway Migration V2: Tamper-Evident Per-Wallet SHA-256 Chain
-- Adds monotonic per-wallet sequence numbers and cryptographic hash links to
-- immutable double-entry journal rows so unauthorized DB edits are detectable.
-- ============================================================================

ALTER TABLE ledger_entries
    ADD COLUMN wallet_sequence BIGINT NOT NULL DEFAULT 1;

ALTER TABLE ledger_entries
    ADD COLUMN previous_hash VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE ledger_entries
    ADD COLUMN entry_hash VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE ledger_entries
    ADD CONSTRAINT uq_ledger_wallet_sequence UNIQUE (wallet_id, wallet_sequence);

CREATE INDEX idx_ledger_entry_hash ON ledger_entries(entry_hash);
