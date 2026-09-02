package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.MerchantCategory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
            rs.getDouble("min_amount"),
            rs.getDouble("max_amount"),
            rs.getString("source"),
            rs.getInt("hit_count"),
            rs.getString("created_at"),
            rs.getString("updated_at")
    );

    /** Grouped so a merchant's bands stay together, then most-used merchants first. */
    public List<MerchantCategory> findAll() {
        return jdbc.query("""
                SELECT * FROM merchant_categories
                ORDER BY merchant_key, min_amount, max_amount
                """, ROW_MAPPER);
    }

    /** Every rule for one merchant, narrowest band first. */
    public List<MerchantCategory> findByKey(String merchantKey) {
        return jdbc.query(
                "SELECT * FROM merchant_categories WHERE merchant_key = :key ORDER BY min_amount",
                new MapSqlParameterSource("key", merchantKey), ROW_MAPPER);
    }

    public Optional<MerchantCategory> findById(long id) {
        return jdbc.query("SELECT * FROM merchant_categories WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /**
     * Loads the whole table as a lookup, so categorizing a batch is one query.
     *
     * <p>A merchant maps to a list rather than a single entry now that it can have several
     * bands; {@link MerchantCategory#bestMatch} chooses between them per transaction.
     */
    public Map<String, List<MerchantCategory>> loadAll() {
        Map<String, List<MerchantCategory>> byKey = new HashMap<>();
        for (MerchantCategory m : findAll()) {
            byKey.computeIfAbsent(m.merchantKey(), k -> new ArrayList<>()).add(m);
        }
        return byKey;
    }

    /**
     * Records what a merchant was categorised as, as a catch-all covering every amount.
     *
     * <p>Correcting one transaction says nothing about which amounts the correction applies to,
     * so it deliberately does not create a band — banded rules are written explicitly through
     * {@link #saveRule}.
     *
     * <p>A 'user' correction always wins: it overwrites anything already stored for that band.
     * An 'ai' answer only fills a gap, so a category the user fixed by hand is never quietly
     * reverted by a later model run.
     */
    public void remember(String merchantKey, String category, String source) {
        saveRule(merchantKey, category, 0, MerchantCategory.UNBOUNDED, source);
    }

    /**
     * Creates or replaces one rule. The conflict target is the whole band, not the merchant, so
     * a new band sits alongside existing ones instead of replacing them.
     */
    public void saveRule(String merchantKey, String category, double minAmount, double maxAmount, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("key", merchantKey)
                .addValue("category", category)
                .addValue("min", minAmount)
                .addValue("max", maxAmount);

        if (MerchantCategory.SOURCE_USER.equals(source)) {
            jdbc.update("""
                    INSERT INTO merchant_categories
                      (merchant_key, category, min_amount, max_amount, source, hit_count, updated_at)
                    VALUES (:key, :category, :min, :max, 'user', 0, datetime('now'))
                    ON CONFLICT(merchant_key, min_amount, max_amount) DO UPDATE SET
                      category = excluded.category,
                      source = 'user',
                      updated_at = datetime('now')
                    """, params);
        } else {
            jdbc.update("""
                    INSERT INTO merchant_categories
                      (merchant_key, category, min_amount, max_amount, source, hit_count, updated_at)
                    VALUES (:key, :category, :min, :max, 'ai', 0, datetime('now'))
                    ON CONFLICT(merchant_key, min_amount, max_amount) DO NOTHING
                    """, params);
        }
    }

    /**
     * Counts how often memory answered instead of the model, for the management view.
     *
     * <p>Keyed by row id rather than merchant: a merchant with several bands would otherwise
     * have every one of its rules credited for a hit only one of them answered.
     */
    public void recordHits(Map<Long, Integer> hitsById) {
        if (hitsById.isEmpty()) return;
        MapSqlParameterSource[] params = hitsById.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.getKey())
                        .addValue("hits", e.getValue()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(
                "UPDATE merchant_categories SET hit_count = hit_count + :hits WHERE id = :id",
                params);
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM merchant_categories WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    public int deleteAll() {
        Integer before = count();
        jdbc.getJdbcTemplate().execute("DELETE FROM merchant_categories");
        return before;
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_categories",
                new MapSqlParameterSource(), Integer.class);
        return n != null ? n : 0;
    }
}
