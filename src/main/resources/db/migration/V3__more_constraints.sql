-- Database-level backstops for values the application already enforces. Plain (validated) ADD CONSTRAINT: if existing
-- rows violate one, the whole migration fails and rolls back (PostgreSQL DDL is transactional), changing nothing.
-- Each statement takes a short exclusive lock and scans its table once.

-- TransactionStatus: PENDING, COMPLETED, FAILED. (transactions.type is already restricted by chk_tx_type_wallets, which
-- only accepts TRANSFER, DEPOSIT and WITHDRAWAL.)
ALTER TABLE transactions ADD CONSTRAINT chk_tx_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'));

-- Role: USER, ADMIN.
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

-- Currency is an upper-case ISO 4217-style code (app.wallet.currency, default USD).
ALTER TABLE wallets ADD CONSTRAINT chk_wallets_currency CHECK (currency ~ '^[A-Z]{3}$');

-- The application forbids transfers to oneself; a deposit or withdrawal has only one wallet, so only rows with both
-- ids are compared.
ALTER TABLE transactions ADD CONSTRAINT chk_tx_no_self_transfer
    CHECK (sender_wallet_id IS NULL OR receiver_wallet_id IS NULL OR sender_wallet_id <> receiver_wallet_id);
