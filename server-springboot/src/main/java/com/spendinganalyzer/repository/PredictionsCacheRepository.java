package com.spendinganalyzer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PredictionsCacheRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PredictionsCacheRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public record CachedEntry(String payload, String generatedAt) {}

    public Optional<CachedEntry> find() {
        List<CachedEntry> rows = jdbc.query(
                "SELECT payload, generated_at FROM predictions_cache WHERE id = 1",
                (rs, rowNum) -> new CachedEntry(rs.getString("payload"), rs.getString("generated_at"))
        );
        return rows.stream().findFirst();
    }

    public void upsert(String payload, String generatedAt) {
        namedJdbc.update(
                """
                INSERT INTO predictions_cache (id, payload, generated_at) VALUES (1, :payload, :generatedAt)
                ON CONFLICT(id) DO UPDATE SET payload = excluded.payload, generated_at = excluded.generated_at
                """,
                new MapSqlParameterSource(Map.of("payload", payload, "generatedAt", generatedAt))
        );
    }
}
