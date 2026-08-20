package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.MerchantCategory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MerchantCategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MerchantCategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MerchantCategory> ROW_MAPPER = (rs, rowNum) -> new MerchantCategory(
            rs.getLong("id"),
            rs.getString("merchant_key"),
            rs.getString("category"),
            rs.getString("source"),
            rs.getInt("hit_count"),
            rs.getString("created_at"),
            rs.getString("updated_at")
    );

    public List<MerchantCategory> findAll() {
        return jdbc.query(
                "SELECT * FROM merchant_categories ORDER BY hit_count DESC, merchant_key",
                ROW_MAPPER);
    }

    public Optional<MerchantCategory> findByKey(String merchantKey) {
        return jdbc.query("SELECT * FROM merchant_categories WHERE merchant_key = :key",
                        new MapSqlParameterSource("key", merchantKey), ROW_MAPPER)
                .stream().findFirst();
    }

    /** Loads the whole table as a lookup map, so categorizing a batch is one query. */
    public Map<String, MerchantCategory> loadAll() {
        Map<String, MerchantCategory> byKey = new HashMap<>();
        for (MerchantCategory m : findAll()) {
            byKey.put(m.merchantKey(), m);
        }
        return byKey;
    }

    /**
     * Records what a merchant was categorised as.
     *
     * <p>A 'user' correction always wins: it overwrites anything already stored. An 'ai'
     * answer only fills a gap — it will not overwrite an existing entry, so a category the
     * user fixed by hand is never quietly reverted by a later model run.
     */
    public void remember(String merchantKey, String category, String source) {
        if (MerchantCategory.SOURCE_USER.equals(source)) {
            jdbc.update("""
                    INSERT INTO merchant_categories (merchant_key, category, source, hit_count, updated_at)
                    VALUES (:key, :category, 'user', 0, datetime('now'))
                    ON CONFLICT(merchant_key) DO UPDATE SET
                      category = excluded.category,
                      source = 'user',
                      updated_at = datetime('now')
                    """,
                    new MapSqlParameterSource().addValue("key", merchantKey).addValue("category", category));
        } else {
            jdbc.update("""
                    INSERT INTO merchant_categories (merchant_key, category, source, hit_count, updated_at)
                    VALUES (:key, :category, 'ai', 0, datetime('now'))
                    ON CONFLICT(merchant_key) DO NOTHING
                    """,
                    new MapSqlParameterSource().addValue("key", merchantKey).addValue("category", category));
        }
    }

    /** Counts how often memory answered instead of the model, for the management view. */
    public void recordHits(Map<String, Integer> hitsByKey) {
        if (hitsByKey.isEmpty()) return;
        MapSqlParameterSource[] params = hitsByKey.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("key", e.getKey())
                        .addValue("hits", e.getValue()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(
                "UPDATE merchant_categories SET hit_count = hit_count + :hits WHERE merchant_key = :key",
                params);
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM merchant_categories WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM merchant_categories", new MapSqlParameterSource());
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_categories",
                new MapSqlParameterSource(), Integer.class);
        return n != null ? n : 0;
    }
}
