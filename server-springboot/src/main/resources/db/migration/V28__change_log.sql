-- A record of changes made to transactions -- edits, recategorizing, deletes, rule re-applies,
-- imports -- so a recent one can be undone. `payload` is JSON: each affected row as it was before
-- and after (null after = deleted), merchant memory as it was for any merchant a correction taught,
-- or the upload batch an import added. Only the newest entries are kept; see ChangeLogService.
CREATE TABLE change_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  action TEXT NOT NULL,
  summary TEXT NOT NULL,
  row_count INTEGER NOT NULL,
  payload TEXT NOT NULL,
  undone_at TEXT
);
