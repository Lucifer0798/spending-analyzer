-- A free-text note on any transaction ("returned the shoes", "Sam owes half"), shown on its row and
-- found by the search box. Separate from split_note, which belongs to a split share and goes with it.
ALTER TABLE transactions ADD COLUMN note TEXT;
