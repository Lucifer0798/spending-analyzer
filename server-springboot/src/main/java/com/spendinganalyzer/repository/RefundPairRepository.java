package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.RefundPair;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class RefundPairRepository {

    /**
     * What confirmed refunds took off the purchase {@code t} -- their statement amounts. A
     * correlated subquery over {@code t}, for SQL that aliases transactions as {@code t}.
     */
    public static final String REFUNDED_AMOUNT = "(SELECT COALESCE(SUM(r.amount), 0) FROM refund_pairs rp"
            + " JOIN transactions r ON r.id = rp.refund_id WHERE rp.purchase_id = t.id AND rp.status = 'confirmed')";

    /** {@code t} isn't refunded in full -- a fully refunded purchase isn't spending at all. */
    public static final String NOT_FULLY_REFUNDED = REFUNDED_AMOUNT + " < t.amount - 0.005";

    /** {@code t} isn't a confirmed refund -- one is money back, not income. */
    public static final String NOT_A_REFUND =
            "NOT EXISTS (SELECT 1 FROM refund_pairs rp WHERE rp.refund_id = t.id AND rp.status = 'confirmed')";

    private final NamedParameterJdbcTemplate jdbc;

    public RefundPairRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RefundPair> ROW_MAPPER = (rs, rowNum) -> new RefundPair(
            rs.getLong("id"),
            rs.getLong("purchase_id"),
            rs.getLong("refund_id"),
            rs.getString("status"),
            rs.getString("created_at"));

    /** Every pair, newest first. */
    public List<RefundPair> findAll() {
        return jdbc.query("SELECT * FROM refund_pairs ORDER BY id DESC", ROW_MAPPER);
    }

    public Optional<RefundPair> findById(long id) {
        return jdbc.query("SELECT * FROM refund_pairs WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /** Ids of every credit that is already a confirmed refund. */
    public Set<Long> confirmedRefundIds() {
        return new HashSet<>(jdbc.queryForList("SELECT refund_id FROM refund_pairs WHERE status = 'confirmed'",
                new MapSqlParameterSource(), Long.class));
    }

    /**
     * For each purchase with confirmed refunds, how much they took off it in cents (the refunds'
     * statement amounts). Purchases without any are absent.
     */
    public Map<Long, Long> refundedCentsByPurchase() {
        Map<Long, Long> cents = new HashMap<>();
        jdbc.query("""
                SELECT p.purchase_id, SUM(CAST(ROUND(r.amount * 100) AS INTEGER)) AS cents
                FROM refund_pairs p JOIN transactions r ON r.id = p.refund_id
                WHERE p.status = 'confirmed'
                GROUP BY p.purchase_id
                """, new MapSqlParameterSource(), rs -> { cents.put(rs.getLong("purchase_id"), rs.getLong("cents")); });
        return cents;
    }

    /** As {@link #refundedCentsByPurchase()}, for just these purchases -- for decorating a page of rows. */
    public Map<Long, Double> refundedByPurchase(Collection<Long> purchaseIds) {
        Map<Long, Double> refunded = new HashMap<>();
        if (purchaseIds.isEmpty()) return refunded;
        jdbc.query("""
                SELECT p.purchase_id, SUM(CAST(ROUND(r.amount * 100) AS INTEGER)) AS cents
                FROM refund_pairs p JOIN transactions r ON r.id = p.refund_id
                WHERE p.status = 'confirmed' AND p.purchase_id IN (:ids)
                GROUP BY p.purchase_id
                """, new MapSqlParameterSource("ids", purchaseIds),
                rs -> { refunded.put(rs.getLong("purchase_id"), rs.getLong("cents") / 100.0); });
        return refunded;
    }

    /** For each of these credits that is a confirmed refund, the description of what it refunds. */
    public Map<Long, String> refundedPurchaseDescriptions(Collection<Long> refundIds) {
        Map<Long, String> found = new HashMap<>();
        if (refundIds.isEmpty()) return found;
        jdbc.query("""
                SELECT p.refund_id, t.description
                FROM refund_pairs p JOIN transactions t ON t.id = p.purchase_id
                WHERE p.status = 'confirmed' AND p.refund_id IN (:ids)
                """, new MapSqlParameterSource("ids", refundIds),
                rs -> { found.put(rs.getLong("refund_id"), rs.getString("description")); });
        return found;
    }

    /** Dismissed pairs as "purchaseId:refundId" keys, for skipping them when suggesting. */
    public Set<String> dismissedKeys() {
        Set<String> keys = new HashSet<>();
        jdbc.query("SELECT purchase_id, refund_id FROM refund_pairs WHERE status = 'dismissed'",
                rs -> { keys.add(key(rs.getLong("purchase_id"), rs.getLong("refund_id"))); });
        return keys;
    }

    public static String key(long purchaseId, long refundId) {
        return purchaseId + ":" + refundId;
    }

    /**
     * Records a pair, replacing an earlier row for the same two transactions -- so confirming a pair
     * once dismissed (or the reverse) is one row, not two.
     */
    public void save(long purchaseId, long refundId, String status) {
        jdbc.update("""
                INSERT INTO refund_pairs (purchase_id, refund_id, status) VALUES (:purchaseId, :refundId, :status)
                ON CONFLICT(purchase_id, refund_id) DO UPDATE SET status = excluded.status, created_at = datetime('now')
                """, new MapSqlParameterSource()
                .addValue("purchaseId", purchaseId)
                .addValue("refundId", refundId)
                .addValue("status", status));
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM refund_pairs WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    /** Drops every pair either transaction belongs to -- called when transactions are deleted. */
    public void deleteForTransactions(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return;
        jdbc.update("DELETE FROM refund_pairs WHERE purchase_id IN (:ids) OR refund_id IN (:ids)",
                new MapSqlParameterSource("ids", transactionIds));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM refund_pairs");
    }

    /** Replaces every pair with exactly what a backup holds, ids included -- a restore, not a merge. */
    public void restoreAll(List<RefundPair> pairs) {
        deleteAll();
        if (pairs == null || pairs.isEmpty()) return;
        MapSqlParameterSource[] params = pairs.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("purchaseId", p.purchaseId())
                        .addValue("refundId", p.refundId())
                        .addValue("status", p.status())
                        .addValue("createdAt", p.createdAt()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO refund_pairs (id, purchase_id, refund_id, status, created_at)
                VALUES (:id, :purchaseId, :refundId, :status, :createdAt)
                """, params);
    }
}
