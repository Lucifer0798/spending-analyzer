package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.RecurringOverride;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RecurringOverrideRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RecurringOverrideRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RecurringOverride> ROW_MAPPER = (rs, rowNum) -> new RecurringOverride(
            rs.getLong("id"),
            rs.getString("merchant_key"),
            rs.getString("action"),
            rs.getString("created_at")
    );

    public List<RecurringOverride> findAll() {
        return jdbc.query("SELECT * FROM recurring_overrides ORDER BY created_at DESC", ROW_MAPPER);
    }

    /** Keyed by merchant, so applying overrides to a detected list is an O(1) lookup each. */
    public Map<String, RecurringOverride> loadAll() {
        Map<String, RecurringOverride> byKey = new HashMap<>();
        for (RecurringOverride o : findAll()) {
            byKey.put(o.merchantKey(), o);
        }
        return byKey;
    }

    /** Creates or replaces the override for a merchant — flagging it "exclude" after "cancel" just changes the action. */
    public RecurringOverride upsert(String merchantKey, String action) {
        jdbc.update("""
                INSERT INTO recurring_overrides (merchant_key, action) VALUES (:key, :action)
                ON CONFLICT(merchant_key) DO UPDATE SET action = excluded.action
                """,
                new MapSqlParameterSource().addValue("key", merchantKey).addValue("action", action));
        return jdbc.query("SELECT * FROM recurring_overrides WHERE merchant_key = :key",
                        new MapSqlParameterSource("key", merchantKey), ROW_MAPPER)
                .stream().findFirst().orElseThrow();
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM recurring_overrides WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }
}
