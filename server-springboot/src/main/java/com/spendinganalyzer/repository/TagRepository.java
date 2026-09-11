package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Tag;
import com.spendinganalyzer.model.TransactionTag;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TagRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TagRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Tag> ROW_MAPPER = (rs, rowNum) -> new Tag(
            rs.getLong("id"), rs.getString("name"), rs.getString("created_at"));

    /** A tag and how many transactions currently carry it. */
    public record TagUsage(String name, int count) {}

    /** Every tag, alphabetical, for a filter dropdown or autocomplete list. */
    public List<TagUsage> findAllWithCounts() {
        return jdbc.getJdbcTemplate().query("""
                SELECT t.name, COUNT(tt.transaction_id) AS n
                FROM tags t
                LEFT JOIN transaction_tags tt ON tt.tag_id = t.id
                GROUP BY t.id
                ORDER BY t.name COLLATE NOCASE
                """, (rs, rowNum) -> new TagUsage(rs.getString("name"), rs.getInt("n")));
    }

    /** Every tag on record, ids included — for the backup export. */
    public List<Tag> findAll() {
        return jdbc.query("SELECT * FROM tags ORDER BY id", ROW_MAPPER);
    }

    /** Every transaction/tag association on record — for the backup export. */
    public List<TransactionTag> findAllAssociations() {
        return jdbc.query("SELECT * FROM transaction_tags ORDER BY transaction_id, tag_id",
                (rs, rowNum) -> new TransactionTag(
                        rs.getLong("transaction_id"), rs.getLong("tag_id"), rs.getString("created_at")));
    }

    public boolean exists(String name) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE name = :name",
                new MapSqlParameterSource("name", name), Integer.class);
        return n != null && n > 0;
    }

    /** A transaction's tags, alphabetical. */
    public List<String> namesFor(long transactionId) {
        return jdbc.query("""
                SELECT t.name FROM tags t
                JOIN transaction_tags tt ON tt.tag_id = t.id
                WHERE tt.transaction_id = :id
                ORDER BY t.name COLLATE NOCASE
                """, new MapSqlParameterSource("id", transactionId), (rs, rowNum) -> rs.getString("name"));
    }

    /**
     * Every one of the given transactions' tags in one query, keyed by transaction id — what
     * lets the transactions list attach tags without one query per row. A transaction with no
     * tags is simply absent from the map rather than mapped to an empty list.
     */
    public Map<Long, List<String>> namesByTransactionId(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) return Map.of();

        record Row(long transactionId, String name) {}
        List<Row> rows = jdbc.query("""
                SELECT tt.transaction_id, t.name
                FROM transaction_tags tt
                JOIN tags t ON t.id = tt.tag_id
                WHERE tt.transaction_id IN (:ids)
                ORDER BY t.name COLLATE NOCASE
                """,
                new MapSqlParameterSource("ids", transactionIds),
                (rs, rowNum) -> new Row(rs.getLong("transaction_id"), rs.getString("name")));

        Map<Long, List<String>> byId = new HashMap<>();
        for (Row row : rows) {
            byId.computeIfAbsent(row.transactionId(), k -> new ArrayList<>()).add(row.name());
        }
        return byId;
    }

    /** Creates the tag if it doesn't already exist (case-insensitively) and returns its id either way. */
    private long findOrCreateId(String name) {
        jdbc.update("INSERT INTO tags (name) VALUES (:name) ON CONFLICT(name) DO NOTHING",
                new MapSqlParameterSource("name", name));
        return jdbc.queryForObject("SELECT id FROM tags WHERE name = :name",
                new MapSqlParameterSource("name", name), Long.class);
    }

    /** Tags a transaction, creating the tag first if this is the first time it's been used. A no-op if already tagged. */
    public void addTag(long transactionId, String tagName) {
        long tagId = findOrCreateId(tagName);
        jdbc.update("""
                INSERT INTO transaction_tags (transaction_id, tag_id) VALUES (:tid, :gid)
                ON CONFLICT(transaction_id, tag_id) DO NOTHING
                """,
                new MapSqlParameterSource().addValue("tid", transactionId).addValue("gid", tagId));
    }

    /** Untags a transaction; the tag itself remains for whatever other transactions carry it. */
    public boolean removeTag(long transactionId, String tagName) {
        return jdbc.update("""
                DELETE FROM transaction_tags
                WHERE transaction_id = :tid AND tag_id = (SELECT id FROM tags WHERE name = :name)
                """,
                new MapSqlParameterSource().addValue("tid", transactionId).addValue("name", tagName)) > 0;
    }

    /** Deletes a tag and every association to it. Returns how many transactions were untagged. */
    public int deleteByName(String name) {
        MapSqlParameterSource params = new MapSqlParameterSource("name", name);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transaction_tags
                WHERE tag_id = (SELECT id FROM tags WHERE name = :name)
                """, params, Integer.class);

        jdbc.update("""
                DELETE FROM transaction_tags
                WHERE tag_id = (SELECT id FROM tags WHERE name = :name)
                """, params);
        jdbc.update("DELETE FROM tags WHERE name = :name", params);

        return count != null ? count : 0;
    }

    /** Replaces every tag and association with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<Tag> tagsToRestore, List<TransactionTag> associationsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM transaction_tags");
        jdbc.getJdbcTemplate().execute("DELETE FROM tags");

        if (!tagsToRestore.isEmpty()) {
            MapSqlParameterSource[] tagParams = tagsToRestore.stream()
                    .map(t -> new MapSqlParameterSource()
                            .addValue("id", t.id())
                            .addValue("name", t.name())
                            .addValue("createdAt", t.createdAt()))
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate(
                    "INSERT INTO tags (id, name, created_at) VALUES (:id, :name, :createdAt)", tagParams);
        }

        if (!associationsToRestore.isEmpty()) {
            MapSqlParameterSource[] assocParams = associationsToRestore.stream()
                    .map(a -> new MapSqlParameterSource()
                            .addValue("tid", a.transactionId())
                            .addValue("gid", a.tagId())
                            .addValue("createdAt", a.createdAt()))
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO transaction_tags (transaction_id, tag_id, created_at)
                    VALUES (:tid, :gid, :createdAt)
                    """, assocParams);
        }
    }
}
