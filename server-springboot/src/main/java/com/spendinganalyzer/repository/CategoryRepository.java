package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Category;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Category> ROW_MAPPER = (rs, rowNum) -> new Category(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getInt("is_builtin") == 1,
            rs.getInt("is_income") == 1,
            rs.getInt("is_transfer") == 1,
            rs.getInt("sort_order")
    );

    public List<Category> findAll() {
        return jdbc.query("SELECT * FROM categories ORDER BY sort_order, name", ROW_MAPPER);
    }

    public List<String> findAllNames() {
        return jdbc.queryForList("SELECT name FROM categories ORDER BY sort_order, name", new MapSqlParameterSource(), String.class);
    }

    public Optional<Category> findById(long id) {
        return jdbc.query("SELECT * FROM categories WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public boolean exists(String name) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE name = :name",
                new MapSqlParameterSource("name", name), Integer.class);
        return n != null && n > 0;
    }

    public boolean nameExists(String name, Long excludingId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("excludingId", excludingId == null ? -1L : excludingId);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE AND id != :excludingId",
                params, Integer.class);
        return n != null && n > 0;
    }

    public Category create(String name, boolean isIncome, boolean isTransfer) {
        jdbc.update("""
                INSERT INTO categories (name, is_builtin, is_income, is_transfer, sort_order)
                VALUES (:name, 0, :isIncome, :isTransfer,
                        COALESCE((SELECT MAX(sort_order) FROM categories), 0) + 1)
                """,
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("isIncome", isIncome ? 1 : 0)
                        .addValue("isTransfer", isTransfer ? 1 : 0));
        Long id = jdbc.getJdbcTemplate().queryForObject("SELECT last_insert_rowid()", Long.class);
        return findById(id != null ? id : 0).orElseThrow();
    }

    /**
     * Renames a category and cascades the new name onto transactions. Transactions store
     * the category name rather than an id, so the rename has to be applied in both places
     * inside one transaction or the two fall out of sync.
     */
    @Transactional
    public void rename(long id, String oldName, String newName) {
        jdbc.update("UPDATE categories SET name = :newName WHERE id = :id",
                new MapSqlParameterSource().addValue("newName", newName).addValue("id", id));
        jdbc.update("UPDATE transactions SET category = :newName WHERE category = :oldName",
                new MapSqlParameterSource().addValue("newName", newName).addValue("oldName", oldName));
    }

    public boolean updateFlags(long id, Boolean isIncome, Boolean isTransfer) {
        List<String> sets = new java.util.ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        if (isIncome != null) {
            sets.add("is_income = :isIncome");
            params.addValue("isIncome", isIncome ? 1 : 0);
        }
        if (isTransfer != null) {
            sets.add("is_transfer = :isTransfer");
            params.addValue("isTransfer", isTransfer ? 1 : 0);
        }
        if (sets.isEmpty()) return false;
        return jdbc.update("UPDATE categories SET " + String.join(", ", sets) + " WHERE id = :id", params) > 0;
    }

    public int transactionCount(String categoryName) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE category = :name",
                new MapSqlParameterSource("name", categoryName), Integer.class);
        return n != null ? n : 0;
    }

    /**
     * Deletes a category and moves any transactions using it to {@code reassignTo},
     * so no transaction is left pointing at a category that no longer exists.
     */
    @Transactional
    public void deleteAndReassign(long id, String name, String reassignTo) {
        jdbc.update("UPDATE transactions SET category = :reassignTo WHERE category = :name",
                new MapSqlParameterSource().addValue("reassignTo", reassignTo).addValue("name", name));
        jdbc.update("DELETE FROM categories WHERE id = :id", new MapSqlParameterSource("id", id));
    }
}
