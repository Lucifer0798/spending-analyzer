package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_WITH_ACCOUNT = """
            SELECT t.*, a.name AS account_name
            FROM transactions t
            LEFT JOIN accounts a ON a.id = t.account_id
            """;

    private static final RowMapper<Transaction> ROW_MAPPER = (rs, rowNum) -> new Transaction(
            rs.getLong("id"),
            rs.getString("date"),
            rs.getString("description"),
            rs.getDouble("amount"),
            rs.getString("type"),
            rs.getString("category"),
            rs.getString("category_source"),
            rs.getString("upload_batch_id"),
            rs.getString("created_at"),
            rs.getLong("account_id"),
            rs.getString("account_name")
    );

    public void insertBatch(List<ParsedTransaction> transactions, String batchId, long accountId) {
        String sql = """
                INSERT INTO transactions (date, description, amount, type, category, category_source, upload_batch_id, account_id)
                VALUES (:date, :description, :amount, :type, :category, :categorySource, :batchId, :accountId)
                """;

        MapSqlParameterSource[] params = transactions.stream()
                .map(t -> new MapSqlParameterSource()
                        .addValue("date", t.date())
                        .addValue("description", t.description())
                        .addValue("amount", t.amount())
                        .addValue("type", t.type())
                        .addValue("category", t.category())
                        .addValue("categorySource", t.category() != null ? "import" : null)
                        .addValue("batchId", batchId)
                        .addValue("accountId", accountId))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate(sql, params);
    }

    /**
     * Counts how many times each duplicate key already exists in the given account,
     * limited to the date range being imported. Keys are built to match
     * {@link ParsedTransaction#dedupeKey()} so the two can be compared directly.
     */
    public Map<String, Integer> countExistingKeys(long accountId, String minDate, String maxDate) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT date, description, amount, type
                FROM transactions
                WHERE account_id = :accountId AND date >= :minDate AND date <= :maxDate
                """,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("minDate", minDate)
                        .addValue("maxDate", maxDate));

        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String key = new ParsedTransaction(
                    (String) row.get("date"),
                    (String) row.get("description"),
                    ((Number) row.get("amount")).doubleValue(),
                    (String) row.get("type"),
                    null
            ).dedupeKey();
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    public List<Transaction> find(String category, String month, Long accountId, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT_WITH_ACCOUNT).append(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = :category");
            params.addValue("category", category);
        }
        if (month != null && !month.isBlank()) {
            sql.append(" AND strftime('%Y-%m', t.date) = :month");
            params.addValue("month", month);
        }
        if (accountId != null) {
            sql.append(" AND t.account_id = :accountId");
            params.addValue("accountId", accountId);
        }
        sql.append(" ORDER BY t.date DESC, t.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);

        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public int count(String category, String month, Long accountId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM transactions t WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = :category");
            params.addValue("category", category);
        }
        if (month != null && !month.isBlank()) {
            sql.append(" AND strftime('%Y-%m', t.date) = :month");
            params.addValue("month", month);
        }
        if (accountId != null) {
            sql.append(" AND t.account_id = :accountId");
            params.addValue("accountId", accountId);
        }

        Integer total = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return total != null ? total : 0;
    }

    public boolean updateCategory(long id, String category, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("category", category)
                .addValue("source", source)
                .addValue("id", id);
        return jdbc.update(
                "UPDATE transactions SET category = :category, category_source = :source WHERE id = :id",
                params) > 0;
    }

    public List<Transaction> findUncategorized() {
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.category IS NULL ORDER BY t.id", ROW_MAPPER);
    }

    /** All debit rows in spend categories (income and transfers excluded), for recurring detection. */
    public List<Transaction> findSpendingTransactions(Long accountId) {
        StringBuilder sql = new StringBuilder(SELECT_WITH_ACCOUNT).append("""
                 LEFT JOIN categories c ON c.name = t.category
                 WHERE t.type = 'debit'
                   AND COALESCE(c.is_income, 0) = 0
                   AND COALESCE(c.is_transfer, 0) = 0
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (accountId != null) {
            sql.append(" AND t.account_id = :accountId");
            params.addValue("accountId", accountId);
        }
        sql.append(" ORDER BY t.date");
        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public void reassignAccount(long fromAccountId, long toAccountId) {
        jdbc.update("UPDATE transactions SET account_id = :to WHERE account_id = :from",
                new MapSqlParameterSource().addValue("to", toAccountId).addValue("from", fromAccountId));
    }

    public void resetAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM transactions");
        jdbc.getJdbcTemplate().execute("DELETE FROM predictions_cache");
    }
}
