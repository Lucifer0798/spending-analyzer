package com.spendinganalyzer.repository;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_WITH_ACCOUNT = """
            SELECT t.*, a.name AS account_name, a.currency AS account_currency
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
            rs.getString("account_name"),
            rs.getString("account_currency")
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

    /** Shared predicate builder so the list and its count can never drift apart. */
    private static void appendFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String category,
            String month,
            Long accountId,
            DateRange range
    ) {
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
        if (range != null && range.from() != null) {
            sql.append(" AND t.date >= :from");
            params.addValue("from", range.from());
        }
        if (range != null && range.to() != null) {
            sql.append(" AND t.date <= :to");
            params.addValue("to", range.to());
        }
    }

    public List<Transaction> find(
            String category, String month, Long accountId, DateRange range, int limit, int offset) {
        return find(category, month, accountId, range, null, null, limit, offset);
    }

    /** Same as above, further filtered to transactions carrying a given tag. */
    public List<Transaction> find(
            String category, String month, Long accountId, DateRange range, String tag, int limit, int offset) {
        return find(category, month, accountId, range, tag, null, limit, offset);
    }

    /** Same as above, further filtered to descriptions containing a search term. */
    public List<Transaction> find(
            String category, String month, Long accountId, DateRange range,
            String tag, String search, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT_WITH_ACCOUNT).append(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, category, month, accountId, range);
        appendTagFilter(sql, params, tag);
        appendSearchFilter(sql, params, search);

        sql.append(" ORDER BY t.date DESC, t.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);

        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public int count(String category, String month, Long accountId, DateRange range) {
        return count(category, month, accountId, range, null, null);
    }

    public int count(String category, String month, Long accountId, DateRange range, String tag) {
        return count(category, month, accountId, range, tag, null);
    }

    public int count(String category, String month, Long accountId, DateRange range, String tag, String search) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM transactions t WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, category, month, accountId, range);
        appendTagFilter(sql, params, tag);
        appendSearchFilter(sql, params, search);

        Integer total = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return total != null ? total : 0;
    }

    /**
     * An EXISTS subquery rather than a JOIN: a transaction can carry several tags, and joining
     * would multiply each matching row once per tag it has, corrupting both the list and the count.
     */
    private static void appendTagFilter(StringBuilder sql, MapSqlParameterSource params, String tag) {
        if (tag != null && !tag.isBlank()) {
            sql.append("""
                     AND EXISTS (
                       SELECT 1 FROM transaction_tags tt
                       JOIN tags tg ON tg.id = tt.tag_id
                       WHERE tt.transaction_id = t.id AND tg.name = :tag
                     )
                    """);
            params.addValue("tag", tag);
        }
    }

    /**
     * SQLite's LIKE is already case-insensitive for ASCII, so no COLLATE or LOWER() is needed.
     * The search term's own {@code %}/{@code _} wildcard characters are escaped so a term like
     * "50% off" matches that literal text rather than being read as a wildcard pattern.
     */
    private static void appendSearchFilter(StringBuilder sql, MapSqlParameterSource params, String search) {
        if (search != null && !search.isBlank()) {
            String escaped = search.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            sql.append(" AND t.description LIKE :search ESCAPE '\\'");
            params.addValue("search", "%" + escaped + "%");
        }
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

    /**
     * Updates the editable fields of a transaction. Only non-null values are applied, so a
     * caller can change the amount without having to resend the whole row.
     *
     * @return false when the transaction does not exist or nothing was supplied to change
     */
    public boolean updateFields(long id, String date, String description, Double amount, String type) {
        List<String> sets = new java.util.ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);

        if (date != null) {
            sets.add("date = :date");
            params.addValue("date", date);
        }
        if (description != null) {
            sets.add("description = :description");
            params.addValue("description", description);
        }
        if (amount != null) {
            sets.add("amount = :amount");
            params.addValue("amount", amount);
        }
        if (type != null) {
            sets.add("type = :type");
            params.addValue("type", type);
        }
        if (sets.isEmpty()) return false;

        return jdbc.update("UPDATE transactions SET " + String.join(", ", sets) + " WHERE id = :id", params) > 0;
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM transactions WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    public Optional<Transaction> findById(long id) {
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public List<Transaction> findUncategorized() {
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.category IS NULL ORDER BY t.id", ROW_MAPPER);
    }

    /** All debit rows in spend categories (income and transfers excluded), for recurring detection. */
    public List<Transaction> findSpendingTransactions(Long accountId, DateRange range) {
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
        if (range != null && range.from() != null) {
            sql.append(" AND t.date >= :from");
            params.addValue("from", range.from());
        }
        if (range != null && range.to() != null) {
            sql.append(" AND t.date <= :to");
            params.addValue("to", range.to());
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

    /**
     * Replaces every transaction with exactly what a backup holds, ids included — a restore, not
     * a merge. Only the stored columns are written; {@code account_name}/{@code account_currency}
     * on the model are joined in at read time and have no column of their own to insert into.
     */
    public void restoreAll(List<Transaction> transactionsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM transactions");
        if (transactionsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = transactionsToRestore.stream()
                .map(t -> new MapSqlParameterSource()
                        .addValue("id", t.id())
                        .addValue("date", t.date())
                        .addValue("description", t.description())
                        .addValue("amount", t.amount())
                        .addValue("type", t.type())
                        .addValue("category", t.category())
                        .addValue("categorySource", t.categorySource())
                        .addValue("batchId", t.uploadBatchId())
                        .addValue("createdAt", t.createdAt())
                        .addValue("accountId", t.accountId()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO transactions
                  (id, date, description, amount, type, category, category_source, upload_batch_id, created_at, account_id)
                VALUES
                  (:id, :date, :description, :amount, :type, :category, :categorySource, :batchId, :createdAt, :accountId)
                """, params);
    }
}
