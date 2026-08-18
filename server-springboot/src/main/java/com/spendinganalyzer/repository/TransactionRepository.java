package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Transaction> ROW_MAPPER = (rs, rowNum) -> new Transaction(
            rs.getLong("id"),
            rs.getString("date"),
            rs.getString("description"),
            rs.getDouble("amount"),
            rs.getString("type"),
            rs.getString("category"),
            rs.getString("category_source"),
            rs.getString("upload_batch_id"),
            rs.getString("created_at")
    );

    public void insertBatch(List<ParsedTransaction> transactions, String batchId) {
        String sql = """
                INSERT INTO transactions (date, description, amount, type, category, category_source, upload_batch_id)
                VALUES (:date, :description, :amount, :type, :category, :categorySource, :batchId)
                """;

        MapSqlParameterSource[] params = transactions.stream()
                .map(t -> new MapSqlParameterSource()
                        .addValue("date", t.date())
                        .addValue("description", t.description())
                        .addValue("amount", t.amount())
                        .addValue("type", t.type())
                        .addValue("category", t.category())
                        .addValue("categorySource", t.category() != null ? "import" : null)
                        .addValue("batchId", batchId))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate(sql, params);
    }

    public List<Transaction> find(String category, String month, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = :category");
            params.addValue("category", category);
        }
        if (month != null && !month.isBlank()) {
            sql.append(" AND strftime('%Y-%m', date) = :month");
            params.addValue("month", month);
        }
        sql.append(" ORDER BY date DESC, id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);

        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public int count(String category, String month) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM transactions WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (category != null && !category.isBlank()) {
            sql.append(" AND category = :category");
            params.addValue("category", category);
        }
        if (month != null && !month.isBlank()) {
            sql.append(" AND strftime('%Y-%m', date) = :month");
            params.addValue("month", month);
        }

        Integer total = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return total != null ? total : 0;
    }

    public boolean updateCategory(long id, String category, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("category", category)
                .addValue("source", source)
                .addValue("id", id);
        int changed = jdbc.update(
                "UPDATE transactions SET category = :category, category_source = :source WHERE id = :id",
                params
        );
        return changed > 0;
    }

    public List<Transaction> findUncategorized() {
        return jdbc.query(
                "SELECT * FROM transactions WHERE category IS NULL ORDER BY id",
                ROW_MAPPER
        );
    }

    public void resetAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM transactions");
        jdbc.getJdbcTemplate().execute("DELETE FROM predictions_cache");
    }
}
