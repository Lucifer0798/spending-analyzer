package com.spendinganalyzer.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Friendly names for merchants, keyed by their {@code MerchantNormalizer} key. Keys sharing a name
 * are one merchant to the reports, search and merchant memory -- see V29.
 */
@Repository
public class MerchantNameRepository {

    public record MerchantName(String merchantKey, String displayName) {}

    /**
     * Every name at one moment, for a run that labels many merchants -- loaded once rather than per
     * row. {@link #label} is a merchant's name, or its key when it has none.
     */
    public record Names(Map<String, String> byKey) {

        public static final Names NONE = new Names(Map.of());

        public String label(String merchantKey) {
            return byKey.getOrDefault(merchantKey, merchantKey);
        }

        /** The other keys sharing this key's name, in key order -- empty when it has none. */
        public List<String> mergedWith(String merchantKey) {
            String name = byKey.get(merchantKey);
            if (name == null) return List.of();
            return byKey.entrySet().stream()
                    .filter(e -> e.getValue().equals(name) && !e.getKey().equals(merchantKey))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public MerchantNameRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Names load() {
        Map<String, String> byKey = new HashMap<>();
        jdbc.query("SELECT merchant_key, display_name FROM merchant_names", new MapSqlParameterSource(),
                rs -> { byKey.put(rs.getString("merchant_key"), rs.getString("display_name")); });
        return new Names(byKey);
    }

    public Optional<String> find(String merchantKey) {
        return jdbc.queryForList("SELECT display_name FROM merchant_names WHERE merchant_key = :key",
                new MapSqlParameterSource("key", merchantKey), String.class).stream().findFirst();
    }

    /** Names each key -- one key is a rename, several are a merge. */
    public void setAll(Collection<String> merchantKeys, String displayName) {
        jdbc.batchUpdate("""
                INSERT INTO merchant_names (merchant_key, display_name) VALUES (:key, :name)
                ON CONFLICT(merchant_key) DO UPDATE SET display_name = excluded.display_name
                """, merchantKeys.stream().map(k -> new MapSqlParameterSource()
                .addValue("key", k).addValue("name", displayName)).toArray(MapSqlParameterSource[]::new));
    }

    /** Takes the name off each key, so it shows (and counts) as its own merchant again. */
    public int clearAll(Collection<String> merchantKeys) {
        if (merchantKeys.isEmpty()) return 0;
        return jdbc.update("DELETE FROM merchant_names WHERE merchant_key IN (:keys)",
                new MapSqlParameterSource("keys", merchantKeys));
    }

    /** Keys whose name contains {@code escapedLikePattern} -- already {@code %...%}, escaped with {@code \}. */
    public List<String> keysNamedLike(String escapedLikePattern) {
        return jdbc.queryForList("SELECT merchant_key FROM merchant_names WHERE display_name LIKE :p ESCAPE '\\'",
                new MapSqlParameterSource("p", escapedLikePattern), String.class);
    }

    public List<MerchantName> findAll() {
        return jdbc.query("SELECT merchant_key, display_name FROM merchant_names ORDER BY merchant_key",
                (rs, n) -> new MerchantName(rs.getString("merchant_key"), rs.getString("display_name")));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().execute("DELETE FROM merchant_names");
    }

    // --- look-alike suggestions turned down ----------------------------------------------------

    public Set<String> dismissedStems() {
        return new HashSet<>(jdbc.queryForList("SELECT stem FROM merchant_suggestion_dismissals ORDER BY stem",
                new MapSqlParameterSource(), String.class));
    }

    public void dismissStem(String stem) {
        jdbc.update("INSERT INTO merchant_suggestion_dismissals (stem) VALUES (:stem) ON CONFLICT(stem) DO NOTHING",
                new MapSqlParameterSource("stem", stem));
    }

    /** As {@link #restoreAll}, for the dismissed suggestions. Null is none. */
    public void restoreDismissedStems(Collection<String> stems) {
        jdbc.getJdbcTemplate().execute("DELETE FROM merchant_suggestion_dismissals");
        if (stems == null || stems.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO merchant_suggestion_dismissals (stem) VALUES (:stem)",
                stems.stream().map(s -> new MapSqlParameterSource("stem", s)).toArray(MapSqlParameterSource[]::new));
    }

    /** Replaces every name with exactly what a backup holds -- a restore, not a merge. Null is none. */
    public void restoreAll(List<MerchantName> names) {
        deleteAll();
        if (names == null || names.isEmpty()) return;
        List<MapSqlParameterSource> rows = new ArrayList<>();
        for (MerchantName n : names) {
            rows.add(new MapSqlParameterSource().addValue("key", n.merchantKey()).addValue("name", n.displayName()));
        }
        jdbc.batchUpdate("INSERT INTO merchant_names (merchant_key, display_name) VALUES (:key, :name)",
                rows.toArray(MapSqlParameterSource[]::new));
    }
}
