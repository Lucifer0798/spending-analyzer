package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.GoalContribution;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class GoalContributionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GoalContributionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<GoalContribution> ROW_MAPPER = (rs, rowNum) -> new GoalContribution(
            rs.getLong("id"),
            rs.getLong("goal_id"),
            rs.getDouble("amount"),
            rs.getString("date"),
            rs.getString("note"),
            rs.getString("created_at")
    );

    /** A goal's sum and count, kept together since {@link #totalsByGoal} always produces both at once. */
    public record GoalTotals(double sum, int count) {
        public static final GoalTotals NONE = new GoalTotals(0, 0);
    }

    /** Every contribution across every goal, for the backup export. */
    public List<GoalContribution> findAll() {
        return jdbc.query("SELECT * FROM goal_contributions ORDER BY date, id", ROW_MAPPER);
    }

    /** Newest first, for a goal's own history. */
    public List<GoalContribution> findByGoalId(long goalId) {
        return jdbc.query(
                "SELECT * FROM goal_contributions WHERE goal_id = :goalId ORDER BY date DESC, id DESC",
                new MapSqlParameterSource("goalId", goalId), ROW_MAPPER);
    }

    /** Every goal's running sum and contribution count in one query, so listing goals costs one query, not one per goal. */
    public Map<Long, GoalTotals> totalsByGoal() {
        Map<Long, GoalTotals> totals = new HashMap<>();
        for (Map<String, Object> row : jdbc.getJdbcTemplate().queryForList(
                "SELECT goal_id, SUM(amount) AS total, COUNT(*) AS n FROM goal_contributions GROUP BY goal_id")) {
            totals.put(
                    ((Number) row.get("goal_id")).longValue(),
                    new GoalTotals(((Number) row.get("total")).doubleValue(), ((Number) row.get("n")).intValue()));
        }
        return totals;
    }

    public GoalContribution add(long goalId, double amount, String date, String note) {
        jdbc.update("""
                INSERT INTO goal_contributions (goal_id, amount, date, note)
                VALUES (:goalId, :amount, :date, :note)
                """,
                new MapSqlParameterSource()
                        .addValue("goalId", goalId)
                        .addValue("amount", amount)
                        .addValue("date", date)
                        .addValue("note", note));
        Long id = jdbc.getJdbcTemplate().queryForObject("SELECT last_insert_rowid()", Long.class);
        return jdbc.query("SELECT * FROM goal_contributions WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst().orElseThrow();
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM goal_contributions WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Deletes every contribution for a goal. Called when the goal itself is deleted — there are
     * no foreign keys in this schema, so nothing else would clean these up on its own.
     */
    public void deleteByGoalId(long goalId) {
        jdbc.update("DELETE FROM goal_contributions WHERE goal_id = :goalId",
                new MapSqlParameterSource("goalId", goalId));
    }

    /** Replaces every contribution with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<GoalContribution> contributionsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM goal_contributions");
        if (contributionsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = contributionsToRestore.stream()
                .map(c -> new MapSqlParameterSource()
                        .addValue("id", c.id())
                        .addValue("goalId", c.goalId())
                        .addValue("amount", c.amount())
                        .addValue("date", c.date())
                        .addValue("note", c.note())
                        .addValue("createdAt", c.createdAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO goal_contributions (id, goal_id, amount, date, note, created_at)
                VALUES (:id, :goalId, :amount, :date, :note, :createdAt)
                """, params);
    }
}
