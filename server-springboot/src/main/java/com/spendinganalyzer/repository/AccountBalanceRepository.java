package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.AccountBalance;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AccountBalanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AccountBalanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AccountBalance> ROW_MAPPER = (rs, rowNum) -> new AccountBalance(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("date"),
            rs.getDouble("balance"),
            rs.getString("created_at")
    );

    /** One account's balance as of one date, joined with the account it belongs to. */
    public record BalanceRow(long accountId, String accountName, String currency, String date, double balance) {}

    /**
     * Sets an account's balance as of a date, replacing whatever was logged for that same day.
     * Upsert rather than separate create/update calls: "Checking is $5,230 today" is one intent,
     * and a second entry the same day is a correction, not a second fact.
     */
    public AccountBalance upsert(long accountId, String date, double balance) {
        jdbc.update("""
                INSERT INTO account_balances (account_id, date, balance) VALUES (:accountId, :date, :balance)
                ON CONFLICT(account_id, date) DO UPDATE SET balance = excluded.balance
                """,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("date", date)
                        .addValue("balance", balance));
        return jdbc.query("SELECT * FROM account_balances WHERE account_id = :accountId AND date = :date",
                        new MapSqlParameterSource().addValue("accountId", accountId).addValue("date", date),
                        ROW_MAPPER)
                .stream().findFirst().orElseThrow();
    }

    /** One account's own history, newest first. */
    public List<AccountBalance> findByAccountId(long accountId) {
        return jdbc.query(
                "SELECT * FROM account_balances WHERE account_id = :accountId ORDER BY date DESC, id DESC",
                new MapSqlParameterSource("accountId", accountId), ROW_MAPPER);
    }

    /**
     * Every balance ever logged for an active (non-archived) account, joined with that account's
     * name and currency, oldest first — the order {@link com.spendinganalyzer.service.NetWorthService}
     * needs to carry the latest-known balance forward through time.
     */
    public List<BalanceRow> findAllForActiveAccounts() {
        return jdbc.query("""
                SELECT ab.account_id, a.name AS account_name, a.currency, ab.date, ab.balance
                FROM account_balances ab
                JOIN accounts a ON a.id = ab.account_id
                WHERE a.archived = 0
                ORDER BY ab.date, ab.account_id
                """,
                (rs, rowNum) -> new BalanceRow(
                        rs.getLong("account_id"),
                        rs.getString("account_name"),
                        rs.getString("currency"),
                        rs.getString("date"),
                        rs.getDouble("balance")));
    }

    /** Every balance on record across every account, for the backup export. */
    public List<AccountBalance> findAll() {
        return jdbc.query("SELECT * FROM account_balances ORDER BY account_id, date", ROW_MAPPER);
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM account_balances WHERE id = :id",
                new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Deletes every balance logged for an account. Called when the account itself is deleted —
     * there are no foreign keys in this schema, so nothing else would clean these up on its own.
     */
    public void deleteByAccountId(long accountId) {
        jdbc.update("DELETE FROM account_balances WHERE account_id = :accountId",
                new MapSqlParameterSource("accountId", accountId));
    }

    /** Replaces every balance with exactly what a backup holds, ids included — a restore, not a merge. */
    public void restoreAll(List<AccountBalance> balancesToRestore) {
        jdbc.getJdbcTemplate().execute("DELETE FROM account_balances");
        if (balancesToRestore.isEmpty()) return;

        MapSqlParameterSource[] params = balancesToRestore.stream()
                .map(b -> new MapSqlParameterSource()
                        .addValue("id", b.id())
                        .addValue("accountId", b.accountId())
                        .addValue("date", b.date())
                        .addValue("balance", b.balance())
                        .addValue("createdAt", b.createdAt()))
                .toArray(MapSqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO account_balances (id, account_id, date, balance, created_at)
                VALUES (:id, :accountId, :date, :balance, :createdAt)
                """, params);
    }
}
