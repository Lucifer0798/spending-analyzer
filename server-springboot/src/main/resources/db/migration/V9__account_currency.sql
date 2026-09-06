-- Every account gets a currency, defaulting to USD so existing accounts (and every transaction
-- already imported under them) keep meaning exactly what they meant before this migration ran.
-- There is no conversion here and never will be automatically: changing an account's currency
-- later changes only how its amounts are displayed and formatted going forward, not the numbers
-- themselves, since there is no exchange-rate history to redo the math with.
ALTER TABLE accounts ADD COLUMN currency TEXT NOT NULL DEFAULT 'USD';
