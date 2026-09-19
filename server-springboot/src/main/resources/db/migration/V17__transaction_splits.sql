-- Optional split on a transaction: split_share is what YOU are responsible for, out of the
-- full transaction amount -- the rest belongs to whoever split_note says it does. NULL means
-- not split, in which case the full amount is your share, exactly today's behaviour unchanged.
-- A note with no share is meaningless, so the two are always set and cleared together.

ALTER TABLE transactions ADD COLUMN split_share REAL;
ALTER TABLE transactions ADD COLUMN split_note TEXT;
