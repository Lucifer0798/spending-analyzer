package com.spendinganalyzer.repository;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.service.MerchantNormalizer;
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
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final MerchantNameRepository merchantNames;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc, MerchantNameRepository merchantNames) {
        this.jdbc = jdbc;
        this.merchantNames = merchantNames;
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
            rs.getString("account_currency"),
            nullableDouble(rs, "split_share"),
            rs.getString("split_note"),
            rs.getString("external_id")
    );

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    public void insertBatch(List<ParsedTransaction> transactions, String batchId, long accountId) {
        String sql = """
                INSERT INTO transactions (date, description, amount, type, category, category_source, upload_batch_id, account_id, external_id)
                VALUES (:date, :description, :amount, :type, :category, :categorySource, :batchId, :accountId, :externalId)
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
                        .addValue("accountId", accountId)
                        .addValue("externalId", t.externalId()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate(sql, params);
    }

    /**
     * Counts how many times each duplicate key already exists in the given account,
     * limited to the date range being imported. Keys are built to match
     * {@link ParsedTransaction#dedupeKey()} so the two can be compared directly.
     */
    public Map<String, Integer> countExistingKeys(long accountId, String minDate, String maxDate) {
        return countExistingKeys(accountId, minDate, maxDate, false);
    }

    /**
     * As above; {@code onlyWithoutExternalId} counts only rows with no bank ID. A file whose rows
     * carry bank IDs is matched on those first, so a stored row that has a <em>different</em> ID is a
     * different transaction, however alike it looks, and mustn't use up a match.
     */
    public Map<String, Integer> countExistingKeys(long accountId, String minDate, String maxDate, boolean onlyWithoutExternalId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT date, description, amount, type
                FROM transactions
                WHERE account_id = :accountId AND date >= :minDate AND date <= :maxDate
                """ + (onlyWithoutExternalId ? " AND external_id IS NULL" : ""),
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

    /** Every transaction in one account going one way, oldest first -- whatever its category. */
    public List<Transaction> findByAccountAndType(long accountId, String type) {
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.account_id = :accountId AND t.type = :type ORDER BY t.date, t.id",
                new MapSqlParameterSource().addValue("accountId", accountId).addValue("type", type), ROW_MAPPER);
    }

    /**
     * What moved through an account after a date: credits minus debits at their full amounts (a
     * split share is what you owe, not what left the account), how many rows, and the newest date.
     */
    public record NetChange(double net, int count, String latestDate) {}

    public NetChange netChangeAfter(long accountId, String afterDate) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN type = 'credit' THEN amount ELSE -amount END), 0) AS net,
                       COUNT(*) AS n, MAX(date) AS latest
                FROM transactions
                WHERE account_id = :accountId AND date > :afterDate
                """,
                new MapSqlParameterSource().addValue("accountId", accountId).addValue("afterDate", afterDate),
                (rs, rowNum) -> new NetChange(rs.getDouble("net"), rs.getInt("n"), rs.getString("latest")));
    }

    /** Which of these bank IDs the account already holds -- a re-imported OFX row is recognised by these. */
    public Set<String> existingExternalIds(long accountId, Collection<String> externalIds) {
        if (externalIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList(
                "SELECT external_id FROM transactions WHERE account_id = :accountId AND external_id IN (:ids)",
                new MapSqlParameterSource().addValue("accountId", accountId).addValue("ids", externalIds),
                String.class));
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
     *
     * <p>A term matching a merchant's name also finds every description that is that merchant --
     * searching "Amazon" finds "AMZN MKTP US*2K4" once that's been named Amazon. The key is
     * computed in Java ({@link MerchantNormalizer}), so those descriptions are worked out here and
     * matched exactly; that only runs when some name matches.
     */
    private void appendSearchFilter(StringBuilder sql, MapSqlParameterSource params, String search) {
        if (search != null && !search.isBlank()) {
            String escaped = search.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            params.addValue("search", pattern);
            List<String> named = descriptionsOfMerchantsNamedLike(pattern);
            if (named.isEmpty()) {
                sql.append(" AND t.description LIKE :search ESCAPE '\\'");
            } else {
                sql.append(" AND (t.description LIKE :search ESCAPE '\\' OR t.description IN (:namedDescriptions))");
                params.addValue("namedDescriptions", named);
            }
        }
    }

    private List<String> descriptionsOfMerchantsNamedLike(String pattern) {
        Set<String> keys = new HashSet<>(merchantNames.keysNamedLike(pattern));
        if (keys.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT DISTINCT description FROM transactions", new MapSqlParameterSource(), String.class)
                .stream()
                .filter(d -> keys.contains(MerchantNormalizer.normalize(d)))
                .toList();
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

    /** Every transaction among the given ids — the source rows a bulk action reads before it acts. */
    public List<Transaction> findByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.id IN (:ids)",
                new MapSqlParameterSource("ids", ids), ROW_MAPPER);
    }

    /** Categorizes several transactions in one statement. Returns how many rows were touched. */
    public int updateCategoryBulk(List<Long> ids, String category, String source) {
        if (ids.isEmpty()) return 0;
        return jdbc.update(
                "UPDATE transactions SET category = :category, category_source = :source WHERE id IN (:ids)",
                new MapSqlParameterSource()
                        .addValue("category", category)
                        .addValue("source", source)
                        .addValue("ids", ids));
    }

    /** Deletes several transactions in one statement. Returns how many rows were removed. */
    public int deleteBulk(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("DELETE FROM transactions WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
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
        return findUncategorized(null);
    }

    /** Every transaction whose category came from {@code source} ('rule', 'user', 'cache', ...). */
    public List<Transaction> findByCategorySource(String source) {
        return jdbc.query(SELECT_WITH_ACCOUNT + " WHERE t.category_source = :source ORDER BY t.id",
                new MapSqlParameterSource("source", source), ROW_MAPPER);
    }

    /** As {@link #findUncategorized()}, limited to one account when {@code accountId} is non-null. */
    public List<Transaction> findUncategorized(Long accountId) {
        String sql = SELECT_WITH_ACCOUNT + " WHERE t.category IS NULL"
                + (accountId != null ? " AND t.account_id = :accountId" : "")
                + " ORDER BY t.id";
        return jdbc.query(sql, new MapSqlParameterSource("accountId", accountId), ROW_MAPPER);
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

    /** All credit rows in income categories, the mirror of {@link #findSpendingTransactions} for detecting recurring income. */
    public List<Transaction> findIncomeTransactions(Long accountId, DateRange range) {
        StringBuilder sql = new StringBuilder(SELECT_WITH_ACCOUNT).append("""
                 LEFT JOIN categories c ON c.name = t.category
                 WHERE t.type = 'credit'
                   AND COALESCE(c.is_income, 0) = 1
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
     * Puts a transaction back exactly as it was, original id included -- undoing a delete. The id
     * is free: AUTOINCREMENT never hands a deleted one out again.
     */
    public void insertWithId(Transaction t) {
        jdbc.update("""
                INSERT INTO transactions
                  (id, date, description, amount, type, category, category_source, upload_batch_id, created_at,
                   account_id, split_share, split_note, external_id)
                VALUES
                  (:id, :date, :description, :amount, :type, :category, :categorySource, :batchId, :createdAt,
                   :accountId, :splitShare, :splitNote, :externalId)
                """, storedColumns(t));
    }

    /** Sets every editable column of a transaction to `t`'s values -- undoing an edit. */
    public void overwrite(Transaction t) {
        jdbc.update("""
                UPDATE transactions SET date = :date, description = :description, amount = :amount, type = :type,
                  category = :category, category_source = :categorySource, account_id = :accountId,
                  split_share = :splitShare, split_note = :splitNote
                WHERE id = :id
                """, storedColumns(t));
    }

    /** Ids of every row an upload added -- what undoing that import removes. */
    public List<Long> idsInBatch(String batchId) {
        return jdbc.queryForList("SELECT id FROM transactions WHERE upload_batch_id = :batchId",
                new MapSqlParameterSource("batchId", batchId), Long.class);
    }

    private static MapSqlParameterSource storedColumns(Transaction t) {
        return new MapSqlParameterSource()
                .addValue("id", t.id())
                .addValue("date", t.date())
                .addValue("description", t.description())
                .addValue("amount", t.amount())
                .addValue("type", t.type())
                .addValue("category", t.category())
                .addValue("categorySource", t.categorySource())
                .addValue("batchId", t.uploadBatchId())
                .addValue("createdAt", t.createdAt())
                .addValue("accountId", t.accountId())
                .addValue("splitShare", t.splitShare())
                .addValue("splitNote", t.splitNote())
                .addValue("externalId", t.externalId());
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
                        .addValue("accountId", t.accountId())
                        .addValue("splitShare", t.splitShare())
                        .addValue("splitNote", t.splitNote())
                        .addValue("externalId", t.externalId()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO transactions
                  (id, date, description, amount, type, category, category_source, upload_batch_id, created_at,
                   account_id, split_share, split_note, external_id)
                VALUES
                  (:id, :date, :description, :amount, :type, :category, :categorySource, :batchId, :createdAt,
                   :accountId, :splitShare, :splitNote, :externalId)
                """, params);
    }

    /**
     * Sets or clears the split -- {@code splitShare} null clears both columns together, since a
     * note with no share is meaningless. The controller resolves what the final pair should be
     * (carrying an existing note forward, validating the share against the amount) before calling
     * this; the repository just writes whatever it's given.
     */
    public boolean updateSplit(long id, Double splitShare, String splitNote) {
        return jdbc.update(
                "UPDATE transactions SET split_share = :splitShare, split_note = :splitNote WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("splitShare", splitShare)
                        .addValue("splitNote", splitNote)) > 0;
    }
}
