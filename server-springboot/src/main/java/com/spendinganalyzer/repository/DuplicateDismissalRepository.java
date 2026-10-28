package com.spendinganalyzer.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups of look-alike charges marked "not a duplicate", keyed by their sorted, comma-joined ids. */
@Repository
public class DuplicateDismissalRepository {

    public record Dismissal(long id, String transactionIds, String createdAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    public DuplicateDismissalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> keys() {
        return new HashSet<>(jdbc.queryForList("SELECT transaction_ids FROM duplicate_dismissals",
                new MapSqlParameterSource(), String.class));
    }

    public void dismiss(String key) {
        jdbc.update("INSERT INTO duplicate_dismissals (transaction_ids) VALUES (:key) ON CONFLICT(transaction_ids) DO NOTHING",
                new MapSqlParameterSource("key", key));
    }

    public List<Dismissal> findAll() {
        return jdbc.query("SELECT * FROM duplicate_dismissals ORDER BY id",
                (rs, n) -> new Dismissal(rs.getLong("id"), rs.getString("transaction_ids"), rs.getString("created_at")));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM duplicate_dismissals");
    }

    /** Replaces every dismissal with exactly what a backup holds -- a restore, not a merge. Null is none. */
    public void restoreAll(List<Dismissal> dismissals) {
        deleteAll();
        if (dismissals == null || dismissals.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO duplicate_dismissals (id, transaction_ids, created_at) VALUES (:id, :ids, :createdAt)",
                dismissals.stream().map(d -> new MapSqlParameterSource()
                        .addValue("id", d.id())
                        .addValue("ids", d.transactionIds())
                        .addValue("createdAt", d.createdAt())).toArray(MapSqlParameterSource[]::new));
    }
}
