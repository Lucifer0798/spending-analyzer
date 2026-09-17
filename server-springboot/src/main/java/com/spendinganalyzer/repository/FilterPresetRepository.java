package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.FilterPreset;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class FilterPresetRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FilterPresetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FilterPreset> ROW_MAPPER = (rs, rowNum) -> new FilterPreset(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("tag"),
            rs.getString("search"),
            rs.getObject("account_id") != null ? rs.getLong("account_id") : null,
            rs.getString("date_from"),
            rs.getString("date_to"),
            rs.getString("created_at")
    );

    public List<FilterPreset> findAll() {
        return jdbc.query("SELECT * FROM filter_presets ORDER BY name COLLATE NOCASE", ROW_MAPPER);
    }

    public Optional<FilterPreset> findById(long id) {
        return jdbc.query("SELECT * FROM filter_presets WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    /**
     * Saves a named combination of filters, replacing whatever was already saved under that name.
     * Upsert rather than separate create/update calls: naming a preset "Business trips" is one
     * intent, whether or not that name already exists.
     */
    public FilterPreset upsert(
            String name, String category, String tag, String search,
            Long accountId, String dateFrom, String dateTo
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("category", category)
                .addValue("tag", tag)
                .addValue("search", search)
                .addValue("accountId", accountId)
                .addValue("dateFrom", dateFrom)
                .addValue("dateTo", dateTo);

        jdbc.update("""
                INSERT INTO filter_presets (name, category, tag, search, account_id, date_from, date_to)
                VALUES (:name, :category, :tag, :search, :accountId, :dateFrom, :dateTo)
                ON CONFLICT(name) DO UPDATE SET
                  category = excluded.category,
                  tag = excluded.tag,
                  search = excluded.search,
                  account_id = excluded.account_id,
                  date_from = excluded.date_from,
                  date_to = excluded.date_to
                """, params);

        return jdbc.query("SELECT * FROM filter_presets WHERE name = :name",
                        new MapSqlParameterSource("name", name), ROW_MAPPER)
                .stream().findFirst().orElseThrow();
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM filter_presets WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Clears the account reference on every preset pointing at a deleted account, back to NULL
     * ("all accounts") — the same reassign-rather-than-orphan choice deleting an account already
     * makes for its transactions, applied here since there's no foreign key to enforce it.
     */
    public void clearAccountReference(long accountId) {
        jdbc.update("UPDATE filter_presets SET account_id = NULL WHERE account_id = :accountId",
                new MapSqlParameterSource("accountId", accountId));
    }

    /** Replaces every preset with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<FilterPreset> presetsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM filter_presets");
        if (presetsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = presetsToRestore.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("id", p.id())
                        .addValue("name", p.name())
                        .addValue("category", p.category())
                        .addValue("tag", p.tag())
                        .addValue("search", p.search())
                        .addValue("accountId", p.accountId())
                        .addValue("dateFrom", p.dateFrom())
                        .addValue("dateTo", p.dateTo())
                        .addValue("createdAt", p.createdAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO filter_presets
                  (id, name, category, tag, search, account_id, date_from, date_to, created_at)
                VALUES
                  (:id, :name, :category, :tag, :search, :accountId, :dateFrom, :dateTo, :createdAt)
                """, params);
    }
}
