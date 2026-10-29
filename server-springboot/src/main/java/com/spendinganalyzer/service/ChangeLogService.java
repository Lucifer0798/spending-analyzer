package com.spendinganalyzer.service;

import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionReceiptRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.repository.TransferPairRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Remembers recent changes to transactions so they can be undone: edits and recategorizing (single
 * and bulk), deletes, re-applying keyword rules, and imports.
 *
 * <p>Undo never overwrites newer work. An edited row is put back only if it still looks exactly as
 * the change left it; anything changed since is skipped and counted. The same goes for merchant
 * memory: a correction's lesson is unlearned only while memory still holds what the correction
 * taught. A deleted row comes back with its original id (AUTOINCREMENT never reuses one), so its
 * tags -- which outlive the row -- reattach; its receipt and any transfer pairing were deleted with
 * it and don't. Undoing an import removes that upload's rows.
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

    record Payload(List<RowChange> rows, List<MemoryChange> memory, String importBatchId, int receiptsLost) {}

    /** What the History list shows. */
    public record Entry(long id, String createdAt, String action, String summary, int rowCount, String undoneAt,
                        int receiptsLost) {}

    /**
     * @param restored rows put back as they were (or re-inserted, for a delete)
     * @param skipped  rows changed again since, or already gone, and so left as they are
     * @param removed  rows an undone import took away
     */
    public record UndoResult(int restored, int skipped, int removed) {}

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

        if (payload.importBatchId() != null) {
            List<Long> ids = transactions.idsInBatch(payload.importBatchId());
            removed = transactions.deleteBulk(ids);
            receipts.deleteBulk(ids);
            transferPairs.deleteForTransactions(ids);
        }

        jdbc.update("UPDATE change_log SET undone_at = datetime('now') WHERE id = :id", new MapSqlParameterSource("id", id));
        return Optional.of(new UndoResult(restored, skipped, removed));
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
