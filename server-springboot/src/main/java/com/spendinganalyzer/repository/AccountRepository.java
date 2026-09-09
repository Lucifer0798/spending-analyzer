package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Account;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("type"),
            rs.getInt("archived") == 1,
            rs.getString("created_at"),
            rs.getString("currency")
    );

    public List<Account> findAll(boolean includeArchived) {
        String sql = includeArchived
                ? "SELECT * FROM accounts ORDER BY archived, name"
                : "SELECT * FROM accounts WHERE archived = 0 ORDER BY name";
        return jdbc.query(sql, ROW_MAPPER);
    }

    public Optional<Account> findById(long id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = :id",
                        new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public boolean existsById(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        return n != null && n > 0;
    }

    public boolean nameExists(String name, Long excludingId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("excludingId", excludingId == null ? -1L : excludingId);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE name = :name COLLATE NOCASE AND id != :excludingId",
                params, Integer.class);
        return n != null && n > 0;
    }

    public Account create(String name, String type, String currency) {
        jdbc.update("INSERT INTO accounts (name, type, currency) VALUES (:name, :type, :currency)",
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("type", type)
                        .addValue("currency", currency));
        Long id = jdbc.getJdbcTemplate().queryForObject("SELECT last_insert_rowid()", Long.class);
        return findById(id != null ? id : 0).orElseThrow();
    }

    public boolean update(long id, String name, String type, Boolean archived, String currency) {
        StringBuilder sql = new StringBuilder("UPDATE accounts SET ");
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<String> sets = new java.util.ArrayList<>();

        if (name != null) {
            sets.add("name = :name");
            params.addValue("name", name);
        }
        if (type != null) {
            sets.add("type = :type");
            params.addValue("type", type);
        }
        if (archived != null) {
            sets.add("archived = :archived");
            params.addValue("archived", archived ? 1 : 0);
        }
        if (currency != null) {
            sets.add("currency = :currency");
            params.addValue("currency", currency);
        }
        if (sets.isEmpty()) return false;

        sql.append(String.join(", ", sets)).append(" WHERE id = :id");
        return jdbc.update(sql.toString(), params) > 0;
    }

    /**
     * Every distinct currency in use across all accounts, archived included — an archived
     * account's past transactions still count toward an "all accounts" total. Used to decide
     * whether that total means anything: one entry means every account agrees, more than one
     * means summing across accounts would mix currencies.
     */
    public List<String> distinctCurrencies() {
        return jdbc.getJdbcTemplate().queryForList("SELECT DISTINCT currency FROM accounts", String.class);
    }

    /** Number of transactions currently assigned to this account. */
    public int transactionCount(long accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE account_id = :id",
                new MapSqlParameterSource("id", accountId), Integer.class);
        return n != null ? n : 0;
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM accounts WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Replaces every account with exactly what a backup holds, ids included — a restore, not a
     * merge. Safe to insert explicit ids into an AUTOINCREMENT column: SQLite advances its own
     * counter to match the highest one used, so accounts created afterward never collide.
     */
    public void restoreAll(List<Account> accountsToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM accounts");
        if (accountsToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = accountsToRestore.stream()
                .map(a -> new MapSqlParameterSource()
                        .addValue("id", a.id())
                        .addValue("name", a.name())
                        .addValue("type", a.type())
                        .addValue("archived", a.archived() ? 1 : 0)
                        .addValue("createdAt", a.createdAt())
                        .addValue("currency", a.currency()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO accounts (id, name, type, archived, created_at, currency)
                VALUES (:id, :name, :type, :archived, :createdAt, :currency)
                """, params);
    }
}
