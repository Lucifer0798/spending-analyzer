package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Budget;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
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
            rs.getString("escalation_type"),
            nullableDouble(rs, "escalation_value"),
            nullableInt(rs, "escalation_frequency_months"),
            rs.getString("escalation_start_month"),
            rs.getString("rollover_start_month"),
            rs.getString("updated_at")
    );

    private static Double nullableDouble(ResultSet rs, String column) throws java.sql.SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

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
     * Sets the target for a category, replacing any existing one -- escalation schedule
     * included, so a save that omits it clears whatever schedule was there before. Upsert
     * rather than separate create and update endpoints because "budget Groceries at 500" is
     * one intent, and the caller should not have to know whether a row already exists.
     *
     * <p>{@code rolloverStartMonth} is the one exception to "omitting it clears it": the
     * controller is responsible for resolving what value to pass here (carrying an existing
     * start month forward, or defaulting a freshly-enabled one to the current month) before
     * calling this -- the repository just writes whatever it's given, the same division of
     * responsibility {@code TransactionRepository.updateSplit} follows.
     */
    public Budget upsert(
            String category,
            double monthlyLimit,
            String escalationType,
            Double escalationValue,
            Integer escalationFrequencyMonths,
            String escalationStartMonth,
            String rolloverStartMonth
    ) {
        jdbc.update("""
                INSERT INTO budgets (
                  category, monthly_limit,
                  escalation_type, escalation_value, escalation_frequency_months, escalation_start_month,
                  rollover_start_month
                ) VALUES (
                  :category, :limit,
                  :escalationType, :escalationValue, :escalationFrequencyMonths, :escalationStartMonth,
                  :rolloverStartMonth
                )
                ON CONFLICT(category) DO UPDATE SET
                  monthly_limit = excluded.monthly_limit,
                  escalation_type = excluded.escalation_type,
                  escalation_value = excluded.escalation_value,
                  escalation_frequency_months = excluded.escalation_frequency_months,
                  escalation_start_month = excluded.escalation_start_month,
                  rollover_start_month = excluded.rollover_start_month,
                  updated_at = datetime('now')
                """,
                new MapSqlParameterSource()
                        .addValue("category", category)
                        .addValue("limit", monthlyLimit)
                        .addValue("escalationType", escalationType)
                        .addValue("escalationValue", escalationValue)
                        .addValue("escalationFrequencyMonths", escalationFrequencyMonths)
                        .addValue("escalationStartMonth", escalationStartMonth)
                        .addValue("rolloverStartMonth", rolloverStartMonth));

        return findByCategory(category).orElseThrow();
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM budgets WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /** Replaces every budget with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<Budget> budgetsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM budgets");
        if (budgetsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = budgetsToRestore.stream()
                .map(b -> new MapSqlParameterSource()
                        .addValue("id", b.id())
                        .addValue("category", b.category())
                        .addValue("limit", b.monthlyLimit())
                        .addValue("escalationType", b.escalationType())
                        .addValue("escalationValue", b.escalationValue())
                        .addValue("escalationFrequencyMonths", b.escalationFrequencyMonths())
                        .addValue("escalationStartMonth", b.escalationStartMonth())
                        .addValue("rolloverStartMonth", b.rolloverStartMonth())
                        .addValue("updatedAt", b.updatedAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO budgets (
                  id, category, monthly_limit,
                  escalation_type, escalation_value, escalation_frequency_months, escalation_start_month,
                  rollover_start_month, updated_at
                ) VALUES (
                  :id, :category, :limit,
                  :escalationType, :escalationValue, :escalationFrequencyMonths, :escalationStartMonth,
                  :rolloverStartMonth, :updatedAt
                )
                """, params);
    }
}
