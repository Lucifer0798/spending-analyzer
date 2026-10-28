package com.spendinganalyzer.service;

import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.DuplicateDismissalRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Charges that look like the same purchase taken twice: debits in one account, at one merchant (the
 * {@link MerchantNormalizer} key merchant memory uses), for the same amount to the cent, each within
 * {@link #WINDOW_DAYS} days of the one before.
 *
 * <p>The thing that makes this useful rather than noisy is leaving habits out. Someone who buys the
 * same $4.50 coffee most mornings has that merchant and amount on many days; two of those a day
 * apart are two coffees, not a double charge. So a merchant-and-amount seen on {@link #HABIT_DAYS}
 * or more distinct days in the account is skipped entirely -- a real double charge at a habitual
 * place goes unflagged, which beats flagging every habit. Transfers and income aren't looked at:
 * they're not charges.
 *
 * <p>Nothing is changed or deleted: a group is a prompt to check with the merchant or bank, and
 * "not a duplicate" ({@link DuplicateDismissalRepository}) stops that exact group being offered.
 */
@Service
public class DuplicateChargeService {

    /** Close enough to be the same purchase posted twice; a statement's pending/posted lag fits inside. */
    static final int WINDOW_DAYS = 2;

    /** Seen on this many distinct days, the same merchant and amount is a habit, not a mistake. */
    static final int HABIT_DAYS = 4;

    public record Group(String merchant, long accountId, double amount, List<Transaction> transactions) {}

    private record Row(long id, long accountId, String merchant, long cents, LocalDate date) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository transactions;
    private final DuplicateDismissalRepository dismissals;

    public DuplicateChargeService(NamedParameterJdbcTemplate jdbc, TransactionRepository transactions,
                                  DuplicateDismissalRepository dismissals) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.dismissals = dismissals;
    }

    /** Every likely double charge, newest first; {@code accountId} null means every account. */
    public List<Group> find(Long accountId) {
        Map<String, List<Row>> byKey = new HashMap<>();
        jdbc.query("""
                SELECT t.id, t.account_id, t.description, t.amount, t.date
                FROM transactions t
                LEFT JOIN categories c ON c.name = t.category
                WHERE t.type = 'debit'
                  AND COALESCE(c.is_transfer, 0) = 0
                  AND COALESCE(c.is_income, 0) = 0
                """ + (accountId != null ? " AND t.account_id = :accountId" : ""),
                new MapSqlParameterSource("accountId", accountId), rs -> {
            LocalDate date = parse(rs.getString("date"));
            if (date == null) return;
            Row row = new Row(rs.getLong("id"), rs.getLong("account_id"),
                    MerchantNormalizer.normalize(rs.getString("description")),
                    Math.round(rs.getDouble("amount") * 100), date);
            byKey.computeIfAbsent(row.accountId() + "|" + row.merchant() + "|" + row.cents(), k -> new ArrayList<>()).add(row);
        });

        Set<String> dismissed = dismissals.keys();
        List<List<Row>> clusters = new ArrayList<>();
        for (List<Row> rows : byKey.values()) {
            if (rows.size() < 2) continue;
            if (rows.stream().map(Row::date).distinct().count() >= HABIT_DAYS) continue;
            rows.sort(Comparator.comparing(Row::date).thenComparingLong(Row::id));
            List<Row> cluster = new ArrayList<>(List.of(rows.get(0)));
            for (int i = 1; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (ChronoUnit.DAYS.between(cluster.get(cluster.size() - 1).date(), r.date()) <= WINDOW_DAYS) {
                    cluster.add(r);
                } else {
                    keep(cluster, dismissed, clusters);
                    cluster = new ArrayList<>(List.of(r));
                }
            }
            keep(cluster, dismissed, clusters);
        }

        Set<Long> ids = new HashSet<>();
        clusters.forEach(c -> c.forEach(r -> ids.add(r.id())));
        Map<Long, Transaction> byId = transactions.findByIds(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));

        return clusters.stream()
                .sorted(Comparator.comparing((List<Row> c) -> c.get(c.size() - 1).date()).reversed()
                        .thenComparing(c -> c.get(0).id(), Comparator.reverseOrder()))
                .map(c -> new Group(c.get(0).merchant(), c.get(0).accountId(), c.get(0).cents() / 100.0,
                        c.stream().map(r -> byId.get(r.id())).toList()))
                .toList();
    }

    private static void keep(List<Row> cluster, Set<String> dismissed, List<List<Row>> into) {
        if (cluster.size() >= 2 && !dismissed.contains(key(cluster.stream().map(Row::id).toList()))) into.add(cluster);
    }

    /** "Not a duplicate" for exactly these transactions. */
    public void dismiss(List<Long> transactionIds) {
        dismissals.dismiss(key(transactionIds));
    }

    /** Sorted, comma-joined ids -- the same group gives the same key whatever order it's sent in. */
    static String key(List<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static LocalDate parse(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }
}
