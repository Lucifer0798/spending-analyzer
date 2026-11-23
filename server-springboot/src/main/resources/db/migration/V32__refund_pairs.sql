-- A refund shows up as a credit -- usually filed under Income -- while the purchase it reverses still
-- counts as spending, so a returned $250 jacket reads as $250 spent and $250 earned. A row here pairs
-- a refund with the purchase it reverses.
--
-- 'confirmed': the refund's amount comes off the purchase in every spending total, and the refund
-- no longer counts as income. Neither transaction is changed -- the pairing is the whole record, so
-- undoing it is just deleting the row. 'dismissed' pairs were judged "not a refund for this" and
-- only exist so the same suggestion isn't made again.
--
-- No foreign keys, like transfer_pairs: deleting a transaction deletes its pairs explicitly.
CREATE TABLE refund_pairs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  purchase_id INTEGER NOT NULL,
  refund_id INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('confirmed', 'dismissed')),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (purchase_id, refund_id)
);

-- A refund reverses at most one purchase. A purchase can have several (two items returned
-- separately), up to its own amount -- checked when confirming.
CREATE UNIQUE INDEX idx_refund_pairs_confirmed_refund ON refund_pairs(refund_id) WHERE status = 'confirmed';
-- Every spending total looks up a purchase's confirmed refunds by purchase_id.
CREATE INDEX idx_refund_pairs_purchase ON refund_pairs(purchase_id, status);
