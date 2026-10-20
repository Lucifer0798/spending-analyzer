package com.spendinganalyzer.service;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How current each account's data is. Everything this app works out -- budgets, forecasts, the
 * low-balance alert -- is only as good as the newest statement imported and balance logged, and
 * neither arrives on its own, so this is what reminds you.
 *
 * <p>A statement is "due" for an account you import into (it has transactions) once its newest
 * transaction is more than {@link #DUE_AFTER_DAYS} days old: statements come monthly, plus a few
 * days' grace. An account with nothing imported isn't nagged -- an investment account you only
 * log balances for, say, has no statements to import. A balance is due on the same clock, only
 * for accounts where you've logged one before.
 */
@Service
public class AccountFreshnessService {

    public static final int DUE_AFTER_DAYS = 35;

    /**
     * @param daysSinceTransaction null when nothing's been imported; never negative (a future-dated
     *                             row counts as today)
     */
    public record AccountFreshness(
            long accountId,
            String accountName,
            String accountType,
            int transactionCount,
            String latestTransactionDate,
            Integer daysSinceTransaction,
            String latestBalanceDate,
            Integer daysSinceBalance,
            boolean statementDue,
            boolean balanceDue
    ) {}

    public record Freshness(String today, int dueAfterDays, List<AccountFreshness> accounts) {}

    private record Latest(String date, int count) {}

    private final AccountRepository accounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AccountFreshnessService(AccountRepository accounts, NamedParameterJdbcTemplate jdbc) {
        this(accounts, jdbc, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today". */
    AccountFreshnessService(AccountRepository accounts, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Freshness freshness() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, Latest> transactions = latestBy("SELECT account_id, MAX(date) AS latest, COUNT(*) AS n FROM transactions GROUP BY account_id");
        Map<Long, Latest> balances = latestBy("SELECT account_id, MAX(date) AS latest, COUNT(*) AS n FROM account_balances GROUP BY account_id");

        List<AccountFreshness> result = accounts.findAll(false).stream().map((Account a) -> {
            Latest t = transactions.get(a.id());
            Latest b = balances.get(a.id());
            Integer sinceTransaction = t == null ? null : daysSince(t.date(), today);
            Integer sinceBalance = b == null ? null : daysSince(b.date(), today);
            return new AccountFreshness(
                    a.id(), a.name(), a.type(),
                    t == null ? 0 : t.count(),
                    t == null ? null : t.date(), sinceTransaction,
                    b == null ? null : b.date(), sinceBalance,
                    sinceTransaction != null && sinceTransaction > DUE_AFTER_DAYS,
                    sinceBalance != null && sinceBalance > DUE_AFTER_DAYS);
        }).toList();

        return new Freshness(today.toString(), DUE_AFTER_DAYS, result);
    }

    private Map<Long, Latest> latestBy(String sql) {
        Map<Long, Latest> byAccount = new HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource(), rs -> {
            byAccount.put(rs.getLong("account_id"), new Latest(rs.getString("latest"), rs.getInt("n")));
        });
        return byAccount;
    }

    private static Integer daysSince(String date, LocalDate today) {
        try {
            return (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(date), today));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
