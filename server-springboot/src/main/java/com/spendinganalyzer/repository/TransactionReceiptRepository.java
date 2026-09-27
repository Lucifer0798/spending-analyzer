package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.TransactionReceipt;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class TransactionReceiptRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionReceiptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransactionReceipt> ROW_MAPPER = (rs, rowNum) -> new TransactionReceipt(
            rs.getLong("transaction_id"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getBytes("data"),
            rs.getString("uploaded_at")
    );

    public Optional<TransactionReceipt> find(long transactionId) {
        return jdbc.query("SELECT * FROM transaction_receipts WHERE transaction_id = :id",
                        new MapSqlParameterSource("id", transactionId), ROW_MAPPER)
                .stream().findFirst();
    }

    public List<TransactionReceipt> findAll() {
        return jdbc.query("SELECT * FROM transaction_receipts ORDER BY transaction_id", ROW_MAPPER);
    }

    /** Replaces any existing receipt for this transaction -- one per transaction, so a re-upload just overwrites. */
    public void upsert(long transactionId, String filename, String contentType, byte[] data) {
        jdbc.update("""
                INSERT INTO transaction_receipts (transaction_id, filename, content_type, data)
                VALUES (:id, :filename, :contentType, :data)
                ON CONFLICT(transaction_id) DO UPDATE SET
                  filename = excluded.filename,
                  content_type = excluded.content_type,
                  data = excluded.data,
                  uploaded_at = datetime('now')
                """,
                new MapSqlParameterSource()
                        .addValue("id", transactionId)
                        .addValue("filename", filename)
                        .addValue("contentType", contentType)
                        .addValue("data", data));
    }

    public boolean delete(long transactionId) {
        return jdbc.update("DELETE FROM transaction_receipts WHERE transaction_id = :id",
                new MapSqlParameterSource("id", transactionId)) > 0;
    }

    /** Called when the transactions themselves are bulk-deleted, so a receipt never outlives the row it belongs to. */
    public void deleteBulk(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return;
        jdbc.update("DELETE FROM transaction_receipts WHERE transaction_id IN (:ids)",
                new MapSqlParameterSource("ids", transactionIds));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM transaction_receipts");
    }

    /** Which of these transactions carry a receipt -- one query for a whole page, not one per row. */
    public Set<Long> transactionIdsWithReceipts(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return Set.of();
        List<Long> found = jdbc.queryForList(
                "SELECT transaction_id FROM transaction_receipts WHERE transaction_id IN (:ids)",
                new MapSqlParameterSource("ids", transactionIds), Long.class);
        return new HashSet<>(found);
    }

    /** Replaces every receipt with exactly what a backup holds -- a restore, not a merge. */
    public void restoreAll(List<TransactionReceipt> receiptsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM transaction_receipts");
        if (receiptsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = receiptsToRestore.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("transactionId", r.transactionId())
                        .addValue("filename", r.filename())
                        .addValue("contentType", r.contentType())
                        .addValue("data", r.data())
                        .addValue("uploadedAt", r.uploadedAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO transaction_receipts (transaction_id, filename, content_type, data, uploaded_at)
                VALUES (:transactionId, :filename, :contentType, :data, :uploadedAt)
                """, params);
    }
}
