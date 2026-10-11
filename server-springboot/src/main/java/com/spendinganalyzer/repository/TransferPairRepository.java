package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.TransferPair;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class TransferPairRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransferPairRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransferPair> ROW_MAPPER = (rs, rowNum) -> new TransferPair(
            rs.getLong("id"),
            rs.getLong("debit_id"),
            rs.getLong("credit_id"),
            rs.getString("status"),
            rs.getString("debit_prev_category"),
            rs.getString("debit_prev_source"),
            rs.getString("credit_prev_category"),
            rs.getString("credit_prev_source"),
            rs.getString("created_at"));

    /** Every pair, newest first. */
    public List<TransferPair> findAll() {
        return jdbc.query("SELECT * FROM transfer_pairs ORDER BY id DESC", ROW_MAPPER);
    }

    public Optional<TransferPair> findById(long id) {
        return jdbc.query("SELECT * FROM transfer_pairs WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /** Ids of every transaction that is already half of a confirmed transfer. */
    public Set<Long> confirmedTransactionIds() {
        Set<Long> ids = new HashSet<>();
        jdbc.query("SELECT debit_id, credit_id FROM transfer_pairs WHERE status = 'confirmed'", rs -> {
            ids.add(rs.getLong("debit_id"));
            ids.add(rs.getLong("credit_id"));
        });
        return ids;
    }

    /** Dismissed pairs as "debitId:creditId" keys, for skipping them when suggesting. */
    public Set<String> dismissedKeys() {
        Set<String> keys = new HashSet<>();
        jdbc.query("SELECT debit_id, credit_id FROM transfer_pairs WHERE status = 'dismissed'",
                rs -> { keys.add(key(rs.getLong("debit_id"), rs.getLong("credit_id"))); });
        return keys;
    }

    public static String key(long debitId, long creditId) {
        return debitId + ":" + creditId;
    }

    /**
     * Records a pair. Replaces an earlier row for the same two transactions, so confirming a pair
     * that was once dismissed (or the reverse) is one row, not two.
     */
    public void save(long debitId, long creditId, String status,
                     String debitPrevCategory, String debitPrevSource,
                     String creditPrevCategory, String creditPrevSource) {
        jdbc.update("""
                INSERT INTO transfer_pairs (debit_id, credit_id, status, debit_prev_category, debit_prev_source,
                                            credit_prev_category, credit_prev_source)
                VALUES (:debitId, :creditId, :status, :debitPrevCategory, :debitPrevSource,
                        :creditPrevCategory, :creditPrevSource)
                ON CONFLICT(debit_id, credit_id) DO UPDATE SET
                    status = excluded.status,
                    debit_prev_category = excluded.debit_prev_category,
                    debit_prev_source = excluded.debit_prev_source,
                    credit_prev_category = excluded.credit_prev_category,
                    credit_prev_source = excluded.credit_prev_source,
                    created_at = datetime('now')
                """, new MapSqlParameterSource()
                .addValue("debitId", debitId)
                .addValue("creditId", creditId)
                .addValue("status", status)
                .addValue("debitPrevCategory", debitPrevCategory)
                .addValue("debitPrevSource", debitPrevSource)
                .addValue("creditPrevCategory", creditPrevCategory)
                .addValue("creditPrevSource", creditPrevSource));
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM transfer_pairs WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    /** Drops every pair either transaction belongs to -- called when transactions are deleted. */
    public void deleteForTransactions(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return;
        jdbc.update("DELETE FROM transfer_pairs WHERE debit_id IN (:ids) OR credit_id IN (:ids)",
                new MapSqlParameterSource("ids", transactionIds));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM transfer_pairs");
    }

    /** Replaces every pair with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<TransferPair> pairs) {
        deleteAll();
        if (pairs == null || pairs.isEmpty()) return;
        MapSqlParameterSource[] params = pairs.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("debitId", p.debitId())
                        .addValue("creditId", p.creditId())
                        .addValue("status", p.status())
                        .addValue("debitPrevCategory", p.debitPrevCategory())
                        .addValue("debitPrevSource", p.debitPrevSource())
                        .addValue("creditPrevCategory", p.creditPrevCategory())
                        .addValue("creditPrevSource", p.creditPrevSource())
                        .addValue("createdAt", p.createdAt()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO transfer_pairs (id, debit_id, credit_id, status, debit_prev_category, debit_prev_source,
                                            credit_prev_category, credit_prev_source, created_at)
                VALUES (:id, :debitId, :creditId, :status, :debitPrevCategory, :debitPrevSource,
                        :creditPrevCategory, :creditPrevSource, :createdAt)
                """, params);
    }
}
