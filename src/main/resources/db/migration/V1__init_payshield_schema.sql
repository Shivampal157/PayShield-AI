-- ============================================================================
-- PayShield AI — Flyway Migration V1: Core FinTech Schema
-- Supports PostgreSQL 16 (and H2 PostgreSQL compatibility mode for tests)
-- ============================================================================

CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_user_accounts_email ON user_accounts(email);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by_token_hash VARCHAR(128),
    ip_address VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_password_reset_user_id ON password_reset_tokens(user_id);

CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    account_number VARCHAR(64) NOT NULL UNIQUE,
    currency VARCHAR(10) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_wallet_user_currency UNIQUE (user_id, currency),
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    sender_wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    recipient_wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    sender_email VARCHAR(255) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    merchant_name VARCHAR(255) NOT NULL,
    merchant_category VARCHAR(64) NOT NULL,
    source_country VARCHAR(8) NOT NULL,
    destination_country VARCHAR(8) NOT NULL,
    ip_distance_km DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    device_trust_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    account_age_days INTEGER NOT NULL DEFAULT 365,
    velocity_count INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    feature_vector_json VARCHAR(1024) NOT NULL,
    risk_factors_json VARCHAR(4000) NOT NULL,
    matched_cases_json VARCHAR(4000) NOT NULL,
    scoring_source VARCHAR(64) NOT NULL,
    analyst_decision VARCHAR(64),
    analyst_notes VARCHAR(1024),
    reviewed_by VARCHAR(255),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    processing_latency_ms BIGINT NOT NULL DEFAULT 0,
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_sender_idempotency UNIQUE (sender_wallet_id, idempotency_key),
    CONSTRAINT chk_payment_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payment_tx_sender ON payment_transactions(sender_wallet_id);
CREATE INDEX idx_payment_tx_recipient ON payment_transactions(recipient_wallet_id);
CREATE INDEX idx_payment_tx_status ON payment_transactions(status);
CREATE INDEX idx_payment_tx_created_at ON payment_transactions(created_at DESC);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    account_number VARCHAR(64) NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    description VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ledger_tx_entry_type UNIQUE (transaction_id, entry_type),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_tx_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_wallet_id ON ledger_entries(wallet_id);
CREATE INDEX idx_ledger_created_at ON ledger_entries(created_at DESC);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    payload_json VARCHAR(4000) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_status_next ON outbox_events(status, next_attempt_at);

CREATE TABLE processed_kafka_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128),
    payload_summary VARCHAR(512),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE security_audit_logs (
    id UUID PRIMARY KEY,
    user_id UUID,
    actor_email VARCHAR(255) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128),
    ip_address VARCHAR(64),
    details VARCHAR(2000),
    correlation_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_created_at ON security_audit_logs(created_at DESC);
CREATE INDEX idx_audit_actor_email ON security_audit_logs(actor_email);
