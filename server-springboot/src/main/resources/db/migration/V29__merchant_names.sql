-- A friendlier name for a merchant, keyed by its MerchantNormalizer key ("AMZN MKTP US" -> "Amazon").
-- Several keys sharing one display_name are merged: top merchants and the year review count them as
-- one place, searching the name finds every spelling, and what merchant memory learned for one
-- spelling answers for the others. Raw descriptions are never changed, and reset keeps these, as it
-- keeps merchant memory: they describe merchants, not the transactions that were cleared.
CREATE TABLE merchant_names (
  merchant_key TEXT PRIMARY KEY,
  display_name TEXT NOT NULL
);

-- Look-alike suggestions ("AMAZON.COM" and "AMAZON MKTP" both start AMAZON) the user said aren't
-- one merchant, by the shared first word -- so the same suggestion isn't offered again.
CREATE TABLE merchant_suggestion_dismissals (
  stem TEXT PRIMARY KEY
);
