package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.NetWorthTarget;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class NetWorthTargetRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NetWorthTargetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NetWorthTarget> ROW_MAPPER = (rs, rowNum) -> new NetWorthTarget(
            rs.getDouble("target_amount"),
            rs.getString("target_date"),
            rs.getString("currency"),
            rs.getString("updated_at")
    );

    public Optional<NetWorthTarget> find() {
        return jdbc.query("SELECT * FROM net_worth_target WHERE id = 1", ROW_MAPPER).stream().findFirst();
    }

    /** Replaces the target outright -- there is only ever one, so setting a new one simply overwrites it. */
    public NetWorthTarget upsert(double targetAmount, String targetDate, String currency) {
        jdbc.update("""
                INSERT INTO net_worth_target (id, target_amount, target_date, currency)
                VALUES (1, :amount, :date, :currency)
                ON CONFLICT(id) DO UPDATE SET
                  target_amount = excluded.target_amount,
                  target_date = excluded.target_date,
                  currency = excluded.currency,
                  updated_at = datetime('now')
                """,
                new MapSqlParameterSource()
                        .addValue("amount", targetAmount)
                        .addValue("date", targetDate)
                        .addValue("currency", currency));
        return find().orElseThrow();
    }

    public boolean delete() {
        return jdbc.getJdbcTemplate().update("DELETE FROM net_worth_target WHERE id = 1") > 0;
    }

    /** Restores from a backup -- at most one entry, the same singleton the table itself enforces. */
    public void restoreAll(NetWorthTarget target) {
        jdbc.getJdbcTemplate().execute("DELETE FROM net_worth_target");
        if (target == null) return;

        jdbc.update("""
                INSERT INTO net_worth_target (id, target_amount, target_date, currency, updated_at)
                VALUES (1, :amount, :date, :currency, :updatedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("amount", target.targetAmount())
                        .addValue("date", target.targetDate())
                        .addValue("currency", target.currency())
                        .addValue("updatedAt", target.updatedAt()));
    }
}
