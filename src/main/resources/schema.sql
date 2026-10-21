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
-- idempotency_key is unique per owning wallet, see uq_tx_owner_idempotency below (not globally).
CREATE TABLE IF NOT EXISTS transactions (
    id                 BIGSERIAL PRIMARY KEY,
    sender_wallet_id   BIGINT         REFERENCES wallets(id),
    receiver_wallet_id BIGINT         REFERENCES wallets(id),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    type               VARCHAR(20)    NOT NULL DEFAULT 'TRANSFER',
    status             VARCHAR(20)    NOT NULL,
    note               VARCHAR(255),
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW(),
    idempotency_key    VARCHAR(100)
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

-- Idempotency keys are scoped per owning wallet: the sender for transfers and withdrawals, the receiver for deposits.
-- The new index is created first, then the old global UNIQUE constraint (auto-named, on this column only) is dropped,
-- so uniqueness is never missing. Old data satisfied the stricter global rule, so the index build cannot fail.
-- The DO bodies are single-quoted (quotes doubled) because Spring's script splitter does not understand $$ blocks.
CREATE UNIQUE INDEX IF NOT EXISTS uq_tx_owner_idempotency
    ON transactions (COALESCE(sender_wallet_id, receiver_wallet_id), idempotency_key)
    WHERE idempotency_key IS NOT NULL;

DO '
DECLARE c text;
BEGIN
    FOR c IN SELECT con.conname FROM pg_constraint con
             JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
             WHERE con.conrelid = ''transactions''::regclass AND con.contype = ''u''
               AND cardinality(con.conkey) = 1 AND a.attname = ''idempotency_key''
    LOOP
        EXECUTE format(''ALTER TABLE transactions DROP CONSTRAINT %I'', c);
    END LOOP;
END';

-- Usernames and emails are unique ignoring case and surrounding whitespace. The check and the trim run only while the
-- index does not exist yet; if existing rows collide, startup stops with a message instead of dropping anything.
DO '
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ''uq_users_username_lower'') THEN
        IF EXISTS (SELECT 1 FROM users GROUP BY lower(btrim(username)) HAVING count(*) > 1) THEN
            RAISE EXCEPTION ''Schema upgrade aborted: several users have the same username ignoring case/whitespace. Rename or remove them first. Find them with: SELECT lower(btrim(username)), count(*) FROM users GROUP BY 1 HAVING count(*) > 1'';
        END IF;
        UPDATE users SET username = btrim(username) WHERE username <> btrim(username);
        CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ''uq_users_email_lower'') THEN
        IF EXISTS (SELECT 1 FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1) THEN
            RAISE EXCEPTION ''Schema upgrade aborted: several users have the same email ignoring case/whitespace. Change or remove them first. Find them with: SELECT lower(btrim(email)), count(*) FROM users GROUP BY 1 HAVING count(*) > 1'';
        END IF;
        UPDATE users SET email = btrim(email) WHERE email <> btrim(email);
        CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
    END IF;
END';
