package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Challenge;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ChallengeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChallengeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Challenge> ROW_MAPPER = (rs, rowNum) -> new Challenge(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getDouble("limit_amount"),
            rs.getString("start_date"),
            rs.getString("end_date"),
            rs.getObject("account_id") == null ? null : rs.getLong("account_id"),
            rs.getString("created_at"));

    /** Newest start first, so what's running now sits above what's finished. */
    public List<Challenge> findAll() {
        return jdbc.query("SELECT * FROM challenges ORDER BY start_date DESC, id DESC", ROW_MAPPER);
    }

    public Optional<Challenge> findById(long id) {
        return jdbc.query("SELECT * FROM challenges WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public Challenge create(String name, String category, double limitAmount, String startDate, String endDate,
                            Long accountId) {
        jdbc.update("""
                INSERT INTO challenges (name, category, limit_amount, start_date, end_date, account_id)
                VALUES (:name, :category, :limit, :start, :end, :accountId)
                """, new MapSqlParameterSource()
                .addValue("name", name).addValue("category", category).addValue("limit", limitAmount)
                .addValue("start", startDate).addValue("end", endDate).addValue("accountId", accountId));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM challenges", new MapSqlParameterSource(), Long.class);
        return findById(id).orElseThrow();
    }

    /** The highest id in use, 0 for none -- a new challenge's id is always above it. */
    public long maxId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM challenges", new MapSqlParameterSource(), Long.class);
        return max == null ? 0 : max;
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM challenges WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    /** Replaces every challenge with exactly what a backup holds -- a restore, not a merge. Null is none. */
    public void restoreAll(List<Challenge> challenges) {
        jdbc.getJdbcTemplate().execute("DELETE FROM challenges");
        if (challenges == null || challenges.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO challenges (id, name, category, limit_amount, start_date, end_date, account_id, created_at)
                VALUES (:id, :name, :category, :limit, :start, :end, :accountId, :createdAt)
                """, challenges.stream().map(c -> new MapSqlParameterSource()
                .addValue("id", c.id()).addValue("name", c.name()).addValue("category", c.category())
                .addValue("limit", c.limitAmount()).addValue("start", c.startDate()).addValue("end", c.endDate())
                .addValue("accountId", c.accountId()).addValue("createdAt", c.createdAt()))
                .toArray(MapSqlParameterSource[]::new));
    }
}
