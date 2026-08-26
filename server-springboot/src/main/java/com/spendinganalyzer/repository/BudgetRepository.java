package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Budget;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class BudgetRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BudgetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Budget> ROW_MAPPER = (rs, rowNum) -> new Budget(
            rs.getLong("id"),
            rs.getString("category"),
            rs.getDouble("monthly_limit"),
            rs.getString("updated_at")
    );

    /** Ordered by the category's own sort order so budgets read in the same order as everything else. */
    public List<Budget> findAll() {
        return jdbc.query("""
                SELECT b.* FROM budgets b
                LEFT JOIN categories c ON c.name = b.category
                ORDER BY COALESCE(c.sort_order, 999), b.category
                """, ROW_MAPPER);
    }

    public Optional<Budget> findById(long id) {
        return jdbc.query("SELECT * FROM budgets WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public Optional<Budget> findByCategory(String category) {
        return jdbc.query("SELECT * FROM budgets WHERE category = :category",
                        new MapSqlParameterSource("category", category), ROW_MAPPER)
                .stream().findFirst();
    }

    /**
     * Sets the target for a category, replacing any existing one. Upsert rather than separate
     * create and update endpoints because "budget Groceries at 500" is one intent, and the
     * caller should not have to know whether a row already exists.
     */
    public Budget upsert(String category, double monthlyLimit) {
        jdbc.update("""
                INSERT INTO budgets (category, monthly_limit) VALUES (:category, :limit)
                ON CONFLICT(category) DO UPDATE SET
                  monthly_limit = excluded.monthly_limit,
                  updated_at = datetime('now')
                """,
                new MapSqlParameterSource()
                        .addValue("category", category)
                        .addValue("limit", monthlyLimit));

        return findByCategory(category).orElseThrow();
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM budgets WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }
}
