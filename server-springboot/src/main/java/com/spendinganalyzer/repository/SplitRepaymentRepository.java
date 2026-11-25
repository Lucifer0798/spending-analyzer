package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.SplitRepayment;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class SplitRepaymentRepository {

    /**
     * {@code t} isn't money paid back toward a split -- the others' money, not income. Only while
     * the charge it repays is still split, so clearing a split brings the credit back as it's filed.
     * For SQL that aliases transactions as {@code t}.
     */
    public static final String NOT_A_REPAYMENT = "NOT EXISTS (SELECT 1 FROM split_repayments sr"
            + " JOIN transactions s ON s.id = sr.transaction_id"
            + " WHERE sr.repayment_id = t.id AND s.split_share IS NOT NULL)";

    private final NamedParameterJdbcTemplate jdbc;

    public SplitRepaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SplitRepayment> ROW_MAPPER = (rs, rowNum) -> new SplitRepayment(
            rs.getLong("id"),
            rs.getLong("transaction_id"),
            rs.getDouble("amount"),
            rs.getString("repaid_on"),
            rs.getObject("repayment_id") == null ? null : rs.getLong("repayment_id"),
            rs.getString("created_at"));

    public List<SplitRepayment> findAll() {
        return jdbc.query("SELECT * FROM split_repayments ORDER BY id", ROW_MAPPER);
    }

    public Optional<SplitRepayment> findById(long id) {
        return jdbc.query("SELECT * FROM split_repayments WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /** The repayments toward these split charges, oldest first. */
    public List<SplitRepayment> findForTransactions(Collection<Long> transactionIds) {
        if (transactionIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM split_repayments WHERE transaction_id IN (:ids) ORDER BY repaid_on, id",
                new MapSqlParameterSource("ids", transactionIds), ROW_MAPPER);
    }

    /** Ids of every credit already linked as a repayment. */
    public Set<Long> linkedRepaymentIds() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT repayment_id FROM split_repayments WHERE repayment_id IS NOT NULL",
                new MapSqlParameterSource(), Long.class));
    }

    /** For each of these credits linked as a repayment, the description of the charge it repays. */
    public Map<Long, String> repaidChargeDescriptions(Collection<Long> creditIds) {
        Map<Long, String> found = new HashMap<>();
        if (creditIds.isEmpty()) return found;
        jdbc.query("""
                SELECT sr.repayment_id, s.description FROM split_repayments sr
                JOIN transactions s ON s.id = sr.transaction_id
                WHERE sr.repayment_id IN (:ids) AND s.split_share IS NOT NULL
                """, new MapSqlParameterSource("ids", creditIds),
                rs -> { found.put(rs.getLong("repayment_id"), rs.getString("description")); });
        return found;
    }

    /** What's been paid back toward each of these charges; absent when nothing has. */
    public Map<Long, Double> repaidByTransaction(Collection<Long> transactionIds) {
        Map<Long, Double> repaid = new HashMap<>();
        if (transactionIds.isEmpty()) return repaid;
        jdbc.query("""
                SELECT transaction_id, SUM(CAST(ROUND(amount * 100) AS INTEGER)) AS cents FROM split_repayments
                WHERE transaction_id IN (:ids) GROUP BY transaction_id
                """, new MapSqlParameterSource("ids", transactionIds),
                rs -> { repaid.put(rs.getLong("transaction_id"), rs.getLong("cents") / 100.0); });
        return repaid;
    }

    public long insert(long transactionId, double amount, String repaidOn, Long repaymentId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO split_repayments (transaction_id, amount, repaid_on, repayment_id)
                VALUES (:transactionId, :amount, :repaidOn, :repaymentId)
                """, new MapSqlParameterSource()
                .addValue("transactionId", transactionId)
                .addValue("amount", amount)
                .addValue("repaidOn", repaidOn)
                .addValue("repaymentId", repaymentId), keys, new String[]{"id"});
        return keys.getKey().longValue();
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM split_repayments WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Called when transactions are deleted: a charge's repayments go with it, and a deleted credit
     * takes its repayment with it -- the record was that credit.
     */
    public void deleteForTransactions(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return;
        jdbc.update("DELETE FROM split_repayments WHERE transaction_id IN (:ids) OR repayment_id IN (:ids)",
                new MapSqlParameterSource("ids", transactionIds));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM split_repayments");
    }

    /** Replaces every row with exactly what a backup holds, ids included -- a restore, not a merge. */
    public void restoreAll(List<SplitRepayment> rows) {
        deleteAll();
        if (rows == null || rows.isEmpty()) return;
        MapSqlParameterSource[] params = rows.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("transactionId", r.transactionId())
                        .addValue("amount", r.amount())
                        .addValue("repaidOn", r.repaidOn())
                        .addValue("repaymentId", r.repaymentId())
                        .addValue("createdAt", r.createdAt()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO split_repayments (id, transaction_id, amount, repaid_on, repayment_id, created_at)
                VALUES (:id, :transactionId, :amount, :repaidOn, :repaymentId, :createdAt)
                """, params);
    }
}
