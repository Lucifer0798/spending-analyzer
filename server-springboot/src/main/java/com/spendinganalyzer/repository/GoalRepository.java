package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Goal;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class GoalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GoalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Goal> ROW_MAPPER = (rs, rowNum) -> new Goal(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getDouble("target_amount"),
            rs.getString("target_date"),
            rs.getString("currency"),
            rs.getString("created_at"),
            rs.getString("updated_at")
    );

    /**
     * Newest first, so a freshly created goal appears at the top. Ordered by id rather than
     * created_at: SQLite's datetime('now') only has second resolution, so two goals created in
     * the same second would otherwise tie.
     */
    public List<Goal> findAll() {
        return jdbc.query("SELECT * FROM goals ORDER BY id DESC", ROW_MAPPER);
    }

    public Optional<Goal> findById(long id) {
        return jdbc.query("SELECT * FROM goals WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public Goal create(String name, double targetAmount, String targetDate, String currency) {
        jdbc.update("""
                INSERT INTO goals (name, target_amount, target_date, currency)
                VALUES (:name, :targetAmount, :targetDate, :currency)
                """,
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("targetAmount", targetAmount)
                        .addValue("targetDate", targetDate)
                        .addValue("currency", currency));
        Long id = jdbc.getJdbcTemplate().queryForObject("SELECT last_insert_rowid()", Long.class);
        return findById(id != null ? id : 0).orElseThrow();
    }

    /**
     * Updates the editable fields of a goal. Only non-null values are applied, so a caller can
     * push back the target date without having to resend the whole thing. Currency isn't
     * editable here -- changing it after contributions were logged in the old one would silently
     * misrepresent every past entry, so a currency change means starting a new goal.
     */
    public boolean update(long id, String name, Double targetAmount, String targetDate) {
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);

        if (name != null) {
            sets.add("name = :name");
            params.addValue("name", name);
        }
        if (targetAmount != null) {
            sets.add("target_amount = :targetAmount");
            params.addValue("targetAmount", targetAmount);
        }
        if (targetDate != null) {
            sets.add("target_date = :targetDate");
            params.addValue("targetDate", targetDate);
        }
        if (sets.isEmpty()) return false;
        sets.add("updated_at = datetime('now')");

        return jdbc.update("UPDATE goals SET " + String.join(", ", sets) + " WHERE id = :id", params) > 0;
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM goals WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /** Replaces every goal with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<Goal> goalsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM goals");
        if (goalsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = goalsToRestore.stream()
                .map(g -> new MapSqlParameterSource()
                        .addValue("id", g.id())
                        .addValue("name", g.name())
                        .addValue("targetAmount", g.targetAmount())
                        .addValue("targetDate", g.targetDate())
                        .addValue("currency", g.currency())
                        .addValue("createdAt", g.createdAt())
                        .addValue("updatedAt", g.updatedAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO goals (id, name, target_amount, target_date, currency, created_at, updated_at)
                VALUES (:id, :name, :targetAmount, :targetDate, :currency, :createdAt, :updatedAt)
                """, params);
    }
}
