-- Token revocation: every JWT carries the user's token_version at issue time and is rejected once the stored
-- value differs. The application bumps it on password change, logout and when an admin disables the user.
-- Existing rows start at 0; tokens issued before this migration have no version claim and are rejected.
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
