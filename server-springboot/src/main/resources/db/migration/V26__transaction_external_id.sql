-- The bank's own ID for a transaction, when the file it came from carries one (an OFX/QFX FITID).
-- A re-imported statement is matched on it first: same account, same ID, same transaction, however
-- its description or posting date shifted between downloads. Null for CSV and Excel rows.
--
-- Not unique: "Skip transactions already imported" can be switched off on purpose, and that must
-- still import rather than fail on a constraint.
ALTER TABLE transactions ADD COLUMN external_id TEXT;

CREATE INDEX idx_transactions_external_id ON transactions(account_id, external_id) WHERE external_id IS NOT NULL;
