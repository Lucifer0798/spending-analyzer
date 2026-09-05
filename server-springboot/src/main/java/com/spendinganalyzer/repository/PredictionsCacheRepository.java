package com.spendinganalyzer.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One cached forecast per account, plus one for "all accounts" combined.
 *
 * <p>Keyed by {@code account_id}, with {@link #ALL_ACCOUNTS} standing in for a null account —
 * see the column comment in {@code V8__predictions_cache_per_account.sql} for why that is a
 * stored sentinel rather than an actual NULL.
 */
@Repository
public class PredictionsCacheRepository {

    /** Sentinel for "no account filter". Real accounts start at 1, so this can never collide. */
    private static final long ALL_ACCOUNTS = 0L;

    private final NamedParameterJdbcTemplate jdbc;

    public PredictionsCacheRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CachedEntry(String payload, String generatedAt) {}

    private static long key(Long accountId) {
        return accountId != null ? accountId : ALL_ACCOUNTS;
    }

    public Optional<CachedEntry> find(Long accountId) {
        List<CachedEntry> rows = jdbc.query(
                "SELECT payload, generated_at FROM predictions_cache WHERE account_id = :accountId",
                new MapSqlParameterSource("accountId", key(accountId)),
                (rs, rowNum) -> new CachedEntry(rs.getString("payload"), rs.getString("generated_at"))
        );
        return rows.stream().findFirst();
    }

    public void upsert(Long accountId, String payload, String generatedAt) {
        jdbc.update(
                """
                INSERT INTO predictions_cache (account_id, payload, generated_at) VALUES (:accountId, :payload, :generatedAt)
                ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload, generated_at = excluded.generated_at
                """,
                new MapSqlParameterSource(Map.of(
                        "accountId", key(accountId),
                        "payload", payload,
                        "generatedAt", generatedAt))
        );
    }
}
