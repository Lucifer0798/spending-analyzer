package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.KeywordRule;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class KeywordRuleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public KeywordRuleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<KeywordRule> ROW_MAPPER = (rs, rowNum) -> new KeywordRule(
            rs.getLong("id"),
            rs.getString("keyword"),
            rs.getString("category"),
            rs.getString("created_at"));

    /**
     * In the order they're tried: longest keyword first, since "PAWS AND CLAWS VET" says more
     * about a transaction than "VET" does; oldest first among equal lengths, so the order is stable.
     */
    public List<KeywordRule> findAll() {
        return jdbc.query("SELECT * FROM keyword_rules ORDER BY length(keyword) DESC, id", ROW_MAPPER);
    }

    public Optional<KeywordRule> findById(long id) {
        return jdbc.query("SELECT * FROM keyword_rules WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /** Case-insensitive, matching the column's NOCASE uniqueness. */
    public boolean keywordExists(String keyword) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM keyword_rules WHERE keyword = :keyword",
                new MapSqlParameterSource("keyword", keyword), Integer.class);
        return n != null && n > 0;
    }

    public KeywordRule create(String keyword, String category) {
        jdbc.update("INSERT INTO keyword_rules (keyword, category) VALUES (:keyword, :category)",
                new MapSqlParameterSource().addValue("keyword", keyword).addValue("category", category));
        return jdbc.query("SELECT * FROM keyword_rules WHERE keyword = :keyword",
                new MapSqlParameterSource("keyword", keyword), ROW_MAPPER).get(0);
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM keyword_rules WHERE id = :id", new MapSqlParameterSource("id", id)) > 0;
    }

    /** Replaces every rule with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<KeywordRule> rules) {
        jdbc.getJdbcTemplate().execute("DELETE FROM keyword_rules");
        if (rules == null || rules.isEmpty()) return;
        MapSqlParameterSource[] params = rules.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("id", r.id())
                        .addValue("keyword", r.keyword())
                        .addValue("category", r.category())
                        .addValue("createdAt", r.createdAt()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO keyword_rules (id, keyword, category, created_at)
                VALUES (:id, :keyword, :category, :createdAt)
                """, params);
    }
}
