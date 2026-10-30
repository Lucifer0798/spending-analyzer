package com.spendinganalyzer.service;

import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionReceiptRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.repository.TransferPairRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Remembers recent changes so they can be undone: to transactions -- edits and recategorizing
 * (single and bulk), deletes, re-applying keyword rules, imports -- and to settings on the Manage
 * page -- categories, budgets, keyword rules, merchant memory and merchant names.
 *
 * <p>Undo never overwrites newer work. An edited row is put back only if it still looks exactly as
 * the change left it; anything changed since is skipped and counted. The same goes for merchant
 * memory: a correction's lesson is unlearned only while memory still holds what the correction
 * taught. A deleted row comes back with its original id (AUTOINCREMENT never reuses one), so its
 * tags -- which outlive the row -- reattach; its receipt and any transfer pairing were deleted with
 * it and don't. Undoing an import removes that upload's rows.
 *
 * <p>A settings change is recorded generically: before the action, {@link #watch} reads the rows it
 * may touch (whole rows, or just the columns it can change); after it, {@link #recordSettings} reads
 * them again and keeps each row that differs, as it was and as it ended up -- null for one created or
 * deleted. Undo follows the same never-overwrite rule: a row is put back only while it still matches
 * how the action left it. A category rename or delete is one such entry spanning every table the
 * rename cascades into.
 *
 * <p>Only the newest {@link #KEEP} entries are kept. Reset and backup restore clear the log: its
 * snapshots would describe a dataset that no longer exists.
 */
@Service
public class ChangeLogService {

    static final int KEEP = 200;

    /** One row: as it was, and as the change left it. {@code after} null means the change deleted it. */
    public record RowChange(Transaction before, Transaction after) {}

    /**
     * A merchant whose catch-all memory a correction set. {@code beforeCategory} null means there
     * was no catch-all rule; {@code afterCategory} is what the correction wrote (always as 'user').
     */
    public record MemoryChange(String merchantKey, String beforeCategory, String beforeSource, String afterCategory) {}

    /**
     * One row of a settings table: {@code before} null means the action created it, {@code after}
     * null that it deleted it. Holds the watched columns only, always including {@code key}.
     */
    public record TableChange(String table, String key, Map<String, Object> before, Map<String, Object> after) {}

    /** {@code tables} is null in entries written before settings were recorded. */
    record Payload(List<RowChange> rows, List<MemoryChange> memory, String importBatchId, int receiptsLost,
                   List<TableChange> tables) {
        Payload(List<RowChange> rows, List<MemoryChange> memory, String importBatchId, int receiptsLost) {
            this(rows, memory, importBatchId, receiptsLost, List.of());
        }
    }

    /**
     * The tables a settings change may be recorded against, with their primary keys -- the only
     * table and column names that ever reach SQL here, so none can come from a request.
     */
    static final Map<String, String> KEYS = Map.of(
            "categories", "id",
            "transactions", "id",
            "budgets", "id",
            "keyword_rules", "id",
            "merchant_categories", "id",
            "transfer_pairs", "id",
            "merchant_names", "merchant_key",
            "merchant_suggestion_dismissals", "stem");

    /** Columns that move on their own -- categorizing bumps a rule's hits -- so they don't count as "changed since". */
    private static final Set<String> VOLATILE = Set.of("hit_count");

    /**
     * Rows of one table an action may change, read before it runs. {@code where} picks them out
     * then, and is run again afterwards to catch rows the action created; rows it moved out of
     * {@code where} (a rename) are re-read by key.
     */
    public final class Watch {
        private final String table;
        private final String key;
        private final String columns;
        private final String where;
        private final Map<String, ?> params;
        private final Map<Object, Map<String, Object>> before;

        private Watch(String table, List<String> columns, String where, Map<String, ?> params) {
            this.table = table;
            this.key = KEYS.get(table);
            if (key == null) throw new IllegalArgumentException("Not a recorded table: " + table);
            this.columns = columns == null ? "*" : String.join(", ", columnsWithKey(key, columns));
            this.where = where;
            this.params = params;
            this.before = byKey(jdbc.queryForList("SELECT " + this.columns + " FROM " + table + " WHERE " + where,
                    new MapSqlParameterSource(params)));
        }

        private List<TableChange> changes() {
            Map<Object, Map<String, Object>> after = byKey(jdbc.queryForList(
                    "SELECT " + columns + " FROM " + table + " WHERE (" + where + ")"
                            + (before.isEmpty() ? "" : " OR " + key + " IN (:watchedKeys)"),
                    new MapSqlParameterSource(params).addValue("watchedKeys", new ArrayList<>(before.keySet()))));
            List<TableChange> changes = new ArrayList<>();
            before.forEach((k, row) -> {
                Map<String, Object> now = after.get(k);
                if (now == null || !sameRow(row, now, false)) changes.add(new TableChange(table, key, row, now));
            });
            after.forEach((k, row) -> {
                if (!before.containsKey(k)) changes.add(new TableChange(table, key, null, row));
            });
            return changes;
        }

        /** The rows as they were before the action -- for describing it. */
        public List<Map<String, Object>> before() {
            return List.copyOf(before.values());
        }

        private Map<Object, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
            Map<Object, Map<String, Object>> map = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) map.put(row.get(key), new LinkedHashMap<>(row));
            return map;
        }
    }

    private static List<String> columnsWithKey(String key, List<String> columns) {
        List<String> all = new ArrayList<>(List.of(key));
        for (String c : columns) if (!c.equals(key)) all.add(c);
        return all;
    }

    /** What the History list shows. */
    public record Entry(long id, String createdAt, String action, String summary, int rowCount, String undoneAt,
                        int receiptsLost) {}

    /**
     * @param restored         transactions put back as they were (or re-inserted, for a delete)
     * @param skipped          rows changed again since, or already gone, and so left as they are
     * @param removed          transactions an undone import took away
     * @param settingsRestored settings rows -- categories, budgets, rules, memory, names -- put back
     */
    public record UndoResult(int restored, int skipped, int removed, int settingsRestored) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionRepository transactions;
    private final MerchantCategoryRepository memory;
    private final TransactionReceiptRepository receipts;
    private final TransferPairRepository transferPairs;

    public ChangeLogService(NamedParameterJdbcTemplate jdbc, ObjectMapper json, TransactionRepository transactions,
                            MerchantCategoryRepository memory, TransactionReceiptRepository receipts,
                            TransferPairRepository transferPairs) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        this.memory = memory;
        this.receipts = receipts;
        this.transferPairs = transferPairs;
    }

    // --- recording --------------------------------------------------------------------------

    /** What a merchant's catch-all memory holds now -- call before a correction overwrites it. */
    public MemoryChange memoryBefore(String merchantKey, String newCategory) {
        Optional<MerchantCategory> current = memory.findCatchAll(merchantKey);
        return new MemoryChange(merchantKey, current.map(MerchantCategory::category).orElse(null),
                current.map(MerchantCategory::source).orElse(null), newCategory);
    }

    /**
     * Rows changed in place. Rows that came out identical are left out; nothing changed means nothing
     * recorded. Returns the entry's id -- what an Undo next to the action calls -- or null for none.
     */
    public Long recordEdit(String action, String summary, List<Transaction> before, List<Transaction> after,
                           List<MemoryChange> memoryChanges) {
        Map<Long, Transaction> afterById = after.stream().collect(Collectors.toMap(Transaction::id, Function.identity()));
        List<RowChange> rows = new ArrayList<>();
        for (Transaction b : before) {
            Transaction a = afterById.get(b.id());
            if (a != null && !sameStored(a, b)) rows.add(new RowChange(b, a));
        }
        List<MemoryChange> realMemory = memoryChanges.stream()
                .filter(m -> !(m.afterCategory().equals(m.beforeCategory()) && MerchantCategory.SOURCE_USER.equals(m.beforeSource())))
                .toList();
        if (rows.isEmpty() && realMemory.isEmpty()) return null;
        return save(action, summary, rows.size(), new Payload(rows, realMemory, null, 0));
    }

    /** As {@link #recordEdit}, for deleted rows. */
    public Long recordDelete(String summary, List<Transaction> deleted, int receiptsLost) {
        if (deleted.isEmpty()) return null;
        return save("delete", summary, deleted.size(),
                new Payload(deleted.stream().map(t -> new RowChange(t, null)).toList(), List.of(), null, receiptsLost));
    }

    /** Reads the rows of {@code table} matching {@code where} -- whole rows -- before an action changes them. */
    public Watch watch(String table, String where, Map<String, ?> params) {
        return new Watch(table, null, where, params);
    }

    /** As above, reading only {@code columns} (plus the key) -- for a table the action only updates. */
    public Watch watch(String table, List<String> columns, String where, Map<String, ?> params) {
        return new Watch(table, columns, where, params);
    }

    /**
     * After the action: whatever the watched rows now differ by. Nothing changed means nothing
     * recorded. Returns the entry's id, or null for none -- as {@link #recordEdit}.
     */
    public Long recordSettings(String action, String summary, Watch... watches) {
        List<TableChange> changes = new ArrayList<>();
        for (Watch w : watches) changes.addAll(w.changes());
        if (changes.isEmpty()) return null;
        return save(action, summary, changes.size(), new Payload(List.of(), List.of(), null, 0, changes));
    }

    /** As {@link #recordEdit}, for an upload's batch. */
    public Long recordImport(String summary, String batchId, int count) {
        if (count == 0) return null;
        return save("import", summary, count, new Payload(List.of(), List.of(), batchId, 0));
    }

    private long save(String action, String summary, int rowCount, Payload payload) {
        jdbc.update("INSERT INTO change_log (action, summary, row_count, payload) VALUES (:action, :summary, :rows, :payload)",
                new MapSqlParameterSource().addValue("action", action).addValue("summary", summary)
                        .addValue("rows", rowCount).addValue("payload", json.writeValueAsString(payload)));
        // One writer at a time, and inside the caller's transaction: the newest row is this one.
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM change_log", new MapSqlParameterSource(), Long.class);
        jdbc.update("DELETE FROM change_log WHERE id NOT IN (SELECT id FROM change_log ORDER BY id DESC LIMIT :keep)",
                new MapSqlParameterSource("keep", KEEP));
        return id;
    }

    // --- reading and undoing ----------------------------------------------------------------

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT id, created_at, action, summary, row_count, payload, undone_at FROM change_log ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                (rs, n) -> new Entry(rs.getLong("id"), rs.getString("created_at"), rs.getString("action"),
                        rs.getString("summary"), rs.getInt("row_count"), rs.getString("undone_at"),
                        json.readValue(rs.getString("payload"), Payload.class).receiptsLost()));
    }

    /**
     * Undoes one entry. Empty for an unknown id.
     *
     * @throws IllegalStateException when it has already been undone
     */
    @Transactional
    public Optional<UndoResult> undo(long id) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT payload, undone_at FROM change_log WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (found.isEmpty()) return Optional.empty();
        if (found.get(0).get("undone_at") != null) throw new IllegalStateException("That change has already been undone.");
        Payload payload = json.readValue((String) found.get(0).get("payload"), Payload.class);

        int restored = 0;
        int skipped = 0;
        int removed = 0;

        for (RowChange change : payload.rows()) {
            Optional<Transaction> current = transactions.findById(change.before().id());
            if (change.after() == null) {
                // A delete: put it back, unless something now has that id (it can't, but don't clobber it).
                if (current.isPresent()) {
                    skipped++;
                } else {
                    transactions.insertWithId(change.before());
                    restored++;
                }
            } else if (current.isPresent() && sameStored(current.get(), change.after())) {
                transactions.overwrite(change.before());
                restored++;
            } else {
                skipped++;
            }
        }

        for (MemoryChange m : payload.memory()) {
            Optional<MerchantCategory> current = memory.findCatchAll(m.merchantKey());
            boolean stillOurs = current.isPresent() && m.afterCategory().equals(current.get().category())
                    && MerchantCategory.SOURCE_USER.equals(current.get().source());
            if (!stillOurs) continue;
            if (m.beforeCategory() == null) memory.deleteCatchAll(m.merchantKey());
            else memory.setCatchAll(m.merchantKey(), m.beforeCategory(), m.beforeSource());
        }

        int settingsRestored = 0;
        List<TableChange> tables = payload.tables() == null ? List.of() : payload.tables();
        // Last change first, so a row an action touched twice unwinds in order.
        for (int i = tables.size() - 1; i >= 0; i--) {
            TableChange change = tables.get(i);
            if (!change.key().equals(KEYS.get(change.table()))) {
                throw new IllegalStateException("Unexpected table or key in a history entry: " + change.table());
            }
            if (putBack(change)) {
                if (change.table().equals("transactions")) restored++;
                else settingsRestored++;
            } else {
                skipped++;
            }
        }

        if (payload.importBatchId() != null) {
            List<Long> ids = transactions.idsInBatch(payload.importBatchId());
            removed = transactions.deleteBulk(ids);
            receipts.deleteBulk(ids);
            transferPairs.deleteForTransactions(ids);
        }

        jdbc.update("UPDATE change_log SET undone_at = datetime('now') WHERE id = :id", new MapSqlParameterSource("id", id));
        return Optional.of(new UndoResult(restored, skipped, removed, settingsRestored));
    }

    /** Puts one settings row back as it was, if it's still as the change left it. False when it isn't. */
    private boolean putBack(TableChange c) {
        Map<String, Object> reference = c.after() != null ? c.after() : c.before();
        Object keyValue = reference.get(c.key());
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT " + String.join(", ", reference.keySet()) + " FROM " + c.table() + " WHERE " + c.key() + " = :k",
                new MapSqlParameterSource("k", keyValue));
        Map<String, Object> current = found.isEmpty() ? null : found.get(0);

        try {
            return putBack(c, current, keyValue);
        } catch (DataAccessException e) {
            // Something since took what this row needs to be unique -- a new category with the old
            // name, a budget made again for it. That's newer work, so it stays. Any other failure
            // is a real error and still fails the undo.
            if (!isConstraintViolation(e)) throw e;
            return false;
        }
    }

    /** SQLite reports a constraint as error 19, which Spring doesn't always translate to its own type. */
    private static boolean isConstraintViolation(DataAccessException e) {
        return e instanceof DataIntegrityViolationException
                || (e.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == SQLITE_CONSTRAINT);
    }

    private static final int SQLITE_CONSTRAINT = 19;

    private boolean putBack(TableChange c, Map<String, Object> current, Object keyValue) {
        if (c.after() == null) {
            // Deleted: bring it back under its old key, unless something has that key now.
            if (current != null) return false;
            insertRow(c.table(), c.before());
            return true;
        }
        if (current == null || !sameRow(current, c.after(), true)) return false;
        if (c.before() == null) {
            jdbc.update("DELETE FROM " + c.table() + " WHERE " + c.key() + " = :k", new MapSqlParameterSource("k", keyValue));
        } else {
            List<String> sets = new ArrayList<>();
            MapSqlParameterSource params = new MapSqlParameterSource("k", keyValue);
            int i = 0;
            for (var e : c.before().entrySet()) {
                if (e.getKey().equals(c.key())) continue;
                sets.add(e.getKey() + " = :v" + i);
                params.addValue("v" + i++, e.getValue());
            }
            jdbc.update("UPDATE " + c.table() + " SET " + String.join(", ", sets) + " WHERE " + c.key() + " = :k", params);
        }
        return true;
    }

    private void insertRow(String table, Map<String, Object> row) {
        List<String> names = new ArrayList<>(row.keySet());
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < names.size(); i++) params.addValue("v" + i, row.get(names.get(i)));
        jdbc.update("INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                + String.join(", ", IntStream.range(0, names.size()).mapToObj(i -> ":v" + i).toList()) + ")", params);
    }

    /**
     * Same values column by column. Numbers compare by value -- JSON brings a stored 3 back as an
     * int or a long -- and {@code ignoreVolatile} skips columns that move on their own.
     */
    private static boolean sameRow(Map<String, Object> a, Map<String, Object> b, boolean ignoreVolatile) {
        for (String column : b.keySet()) {
            if (ignoreVolatile && VOLATILE.contains(column)) continue;
            Object x = a.get(column);
            Object y = b.get(column);
            if (x instanceof Number m && y instanceof Number n) {
                if (Double.compare(m.doubleValue(), n.doubleValue()) != 0) return false;
            } else if (!Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    public void clear() {
        jdbc.getJdbcTemplate().execute("DELETE FROM change_log");
    }

    /** Equal in every stored column -- the joined account name and currency don't count. */
    private static boolean sameStored(Transaction a, Transaction b) {
        return a.id() == b.id()
                && Objects.equals(a.date(), b.date())
                && Objects.equals(a.description(), b.description())
                && a.amount() == b.amount()
                && Objects.equals(a.type(), b.type())
                && Objects.equals(a.category(), b.category())
                && Objects.equals(a.categorySource(), b.categorySource())
                && a.accountId() == b.accountId()
                && Objects.equals(a.splitShare(), b.splitShare())
                && Objects.equals(a.splitNote(), b.splitNote());
    }
}
