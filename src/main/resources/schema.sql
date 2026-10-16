CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS wallets (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT         NOT NULL UNIQUE REFERENCES users(id),
    balance  NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency VARCHAR(3)     NOT NULL DEFAULT 'USD',
    version  BIGINT         NOT NULL DEFAULT 0
);

-- A deposit has no sender wallet and a withdrawal has no receiver wallet, so both ids are nullable.
CREATE TABLE IF NOT EXISTS transactions (
    id                 BIGSERIAL PRIMARY KEY,
    sender_wallet_id   BIGINT         REFERENCES wallets(id),
    receiver_wallet_id BIGINT         REFERENCES wallets(id),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    type               VARCHAR(20)    NOT NULL DEFAULT 'TRANSFER',
    status             VARCHAR(20)    NOT NULL,
    note               VARCHAR(255),
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    idempotency_key    VARCHAR(100)   UNIQUE
);

-- Upgrades for databases created by earlier versions of this script (safe to run repeatedly).
ALTER TABLE users ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS type VARCHAR(20) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS note VARCHAR(255);
ALTER TABLE transactions ALTER COLUMN sender_wallet_id DROP NOT NULL;
ALTER TABLE transactions ALTER COLUMN receiver_wallet_id DROP NOT NULL;
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_tx_type_wallets;
ALTER TABLE transactions ADD CONSTRAINT chk_tx_type_wallets CHECK (
    (type = 'TRANSFER'   AND sender_wallet_id IS NOT NULL AND receiver_wallet_id IS NOT NULL) OR
    (type = 'DEPOSIT'    AND sender_wallet_id IS NULL     AND receiver_wallet_id IS NOT NULL) OR
    (type = 'WITHDRAWAL' AND sender_wallet_id IS NOT NULL AND receiver_wallet_id IS NULL));

CREATE INDEX IF NOT EXISTS idx_tx_sender     ON transactions(sender_wallet_id);
CREATE INDEX IF NOT EXISTS idx_tx_receiver   ON transactions(receiver_wallet_id);
CREATE INDEX IF NOT EXISTS idx_tx_created_at ON transactions(created_at);
