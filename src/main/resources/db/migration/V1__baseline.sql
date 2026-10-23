-- Baseline: the complete schema as of the switch from schema.sql to Flyway. CREATE-only; never edit once applied.
-- Later changes go into new files V2__..., V3__... (see README).

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Usernames and emails are unique ignoring case (the application stores them trimmed).
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE wallets (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT         NOT NULL UNIQUE REFERENCES users(id),
    balance  NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency VARCHAR(3)     NOT NULL DEFAULT 'USD',
    version  BIGINT         NOT NULL DEFAULT 0
);

-- A deposit has no sender wallet and a withdrawal has no receiver wallet, so both ids are nullable.
CREATE TABLE transactions (
    id                 BIGSERIAL PRIMARY KEY,
    sender_wallet_id   BIGINT         REFERENCES wallets(id),
    receiver_wallet_id BIGINT         REFERENCES wallets(id),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    type               VARCHAR(20)    NOT NULL DEFAULT 'TRANSFER',
    status             VARCHAR(20)    NOT NULL,
    note               VARCHAR(255),
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    idempotency_key    VARCHAR(100),
    CONSTRAINT chk_tx_type_wallets CHECK (
        (type = 'TRANSFER'   AND sender_wallet_id IS NOT NULL AND receiver_wallet_id IS NOT NULL) OR
        (type = 'DEPOSIT'    AND sender_wallet_id IS NULL     AND receiver_wallet_id IS NOT NULL) OR
        (type = 'WITHDRAWAL' AND sender_wallet_id IS NOT NULL AND receiver_wallet_id IS NULL))
);

CREATE INDEX idx_tx_sender     ON transactions(sender_wallet_id);
CREATE INDEX idx_tx_receiver   ON transactions(receiver_wallet_id);
CREATE INDEX idx_tx_created_at ON transactions(created_at);

-- Idempotency keys are scoped per owning wallet: the sender for transfers and withdrawals, the receiver for deposits.
CREATE UNIQUE INDEX uq_tx_owner_idempotency
    ON transactions (COALESCE(sender_wallet_id, receiver_wallet_id), idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Deposit requests: a user asks for money to be added; the wallet is credited only when an admin approves. An approved
-- request must point at exactly one DEPOSIT transaction (unique, so a request or a transaction is never linked twice).
CREATE TABLE deposit_requests (
    id              BIGSERIAL PRIMARY KEY,
    wallet_id       BIGINT         NOT NULL REFERENCES wallets(id),
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    note            VARCHAR(255),
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    decided_at      TIMESTAMP,
    decided_by      BIGINT         REFERENCES users(id),
    decision_note   VARCHAR(255),
    transaction_id  BIGINT         REFERENCES transactions(id),
    idempotency_key VARCHAR(100),
    CONSTRAINT chk_dr_decision CHECK (
        (status = 'PENDING'  AND decided_at IS NULL     AND decided_by IS NULL     AND transaction_id IS NULL) OR
        (status = 'APPROVED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND transaction_id IS NOT NULL) OR
        (status = 'REJECTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND transaction_id IS NULL))
);

CREATE INDEX idx_dr_wallet ON deposit_requests(wallet_id);
CREATE INDEX idx_dr_status ON deposit_requests(status);
CREATE UNIQUE INDEX uq_dr_wallet_idempotency
    ON deposit_requests (wallet_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_dr_transaction
    ON deposit_requests (transaction_id) WHERE transaction_id IS NOT NULL;
