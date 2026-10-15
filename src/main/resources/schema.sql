CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS wallets (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT         NOT NULL UNIQUE REFERENCES users(id),
    balance  NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency VARCHAR(3)     NOT NULL DEFAULT 'USD',
    version  BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id                 BIGSERIAL PRIMARY KEY,
    sender_wallet_id   BIGINT         NOT NULL REFERENCES wallets(id),
    receiver_wallet_id BIGINT         NOT NULL REFERENCES wallets(id),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    status             VARCHAR(20)    NOT NULL,
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    idempotency_key    VARCHAR(100)   UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_tx_sender   ON transactions(sender_wallet_id);
CREATE INDEX IF NOT EXISTS idx_tx_receiver ON transactions(receiver_wallet_id);
