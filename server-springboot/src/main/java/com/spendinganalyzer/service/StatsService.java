package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PeriodComparison;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class StatsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountRepository accountRepository;

    public StatsService(NamedParameterJdbcTemplate jdbc, AccountRepository accountRepository) {
        this.jdbc = jdbc;
        this.accountRepository = accountRepository;
    }

    /**
     * Spend excludes anything flagged as income or transfer. The flags live on the
     * categories table rather than being hardcoded names, so a user-created category
     * such as "Moving money to savings" can be excluded from spend totals too.
     *
     * <p>Joins accounts so a currency filter can be applied without every call site needing to
     * know how; accounts are never hard-deleted out from under a transaction (deleting one
     * reassigns its transactions first), so the join is never lossy in practice.
     */
    private static final String SPEND_FILTER = """
            FROM transactions t
            LEFT JOIN categories c ON c.name = t.category
            LEFT JOIN accounts a ON a.id = t.account_id
            WHERE t.type = 'debit'
              AND COALESCE(c.is_income, 0) = 0
              AND COALESCE(c.is_transfer, 0) = 0
            """;

    /** Account, currency and date-range predicates, appended to {@link #SPEND_FILTER}. */
    private static String filters(Long accountId, DateRange range, String currency) {
        StringBuilder sql = new StringBuilder();
        if (accountId != null) sql.append(" AND t.account_id = :accountId");
        if (currency != null) sql.append(" AND a.currency = :currency");
        if (range.from() != null) sql.append(" AND t.date >= :from");
        if (range.to() != null) sql.append(" AND t.date <= :to");
        return sql.toString();
    }

    private static MapSqlParameterSource params(Long accountId, DateRange range, String currency) {
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("currency", currency)
                .addValue("from", range.from())
                .addValue("to", range.to());
    }

    /**
     * The currency totals for this scope are denominated in, or null when {@code accountId} is
     * null and the accounts in the system don't all share one. Summing raw amounts across
     * different currencies would be meaningless, so callers use a null result to decide whether
     * a combined total can be shown at all, falling back to a per-currency breakdown instead.
     */
    public String resolveCurrency(Long accountId) {
        if (accountId != null) {
            return accountRepository.findById(accountId).map(Account::currency).orElse("USD");
        }
        List<String> currencies = accountRepository.distinctCurrencies();
        return currencies.size() == 1 ? currencies.get(0) : null;
    }

    /** Every currency in use, for building a per-currency breakdown when {@link #resolveCurrency} is null. */
    public List<String> currenciesInUse() {
        return accountRepository.distinctCurrencies();
    }

    public List<CategoryTotal> computeCategoryTotals(Long accountId, DateRange range) {
        return computeCategoryTotals(accountId, range, null);
    }

    public List<CategoryTotal> computeCategoryTotals(Long accountId, DateRange range, String currency) {
        String sql = "SELECT COALESCE(t.category, 'Uncategorized') AS category, "
                + "ROUND(SUM(t.amount), 2) AS total, COUNT(*) AS count "
                + SPEND_FILTER + filters(accountId, range, currency)
                + " GROUP BY t.category ORDER BY total DESC";

        return jdbc.query(sql, params(accountId, range, currency), (rs, rowNum) ->
                new CategoryTotal(rs.getString("category"), rs.getDouble("total"), rs.getInt("count")));
    }

    /**
     * The active range against the period immediately before it, of the same length. Needs both
     * bounds set: an unbounded or half-open range has no defined length to mirror, and comparing
     * something arbitrary would be worse than not comparing at all.
     */
    public Optional<PeriodComparison> computeComparison(Long accountId, DateRange range) {
        if (range.from() == null || range.to() == null) {
            return Optional.empty();
        }

        LocalDate from = LocalDate.parse(range.from());
        LocalDate to = LocalDate.parse(range.to());
        long lengthInDays = ChronoUnit.DAYS.between(from, to) + 1;

        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(lengthInDays - 1);
        DateRange previousRange = new DateRange(previousFrom.toString(), previousTo.toString());

        Map<String, Double> current = totalsByCategory(accountId, range);
        Map<String, Double> previous = totalsByCategory(accountId, previousRange);

        TreeSet<String> allCategories = new TreeSet<>();
        allCategories.addAll(current.keySet());
        allCategories.addAll(previous.keySet());

        List<CategoryComparison> categories = new ArrayList<>();
        for (String category : allCategories) {
            double currentTotal = current.getOrDefault(category, 0.0);
            double previousTotal = previous.getOrDefault(category, 0.0);
            categories.add(new CategoryComparison(category, currentTotal, previousTotal,
                    round2(currentTotal - previousTotal), changePercent(currentTotal, previousTotal)));
        }
        // Biggest increase first, biggest decrease last -- the two ends of the list are what a
        // "what changed" view exists to show.
        categories.sort((a, b) -> Double.compare(b.changeAmount(), a.changeAmount()));

        double currentTotal = current.values().stream().mapToDouble(Double::doubleValue).sum();
        double previousTotal = previous.values().stream().mapToDouble(Double::doubleValue).sum();

        return Optional.of(new PeriodComparison(
                range, previousRange,
                round2(currentTotal), round2(previousTotal),
                round2(currentTotal - previousTotal),
                changePercent(currentTotal, previousTotal),
                categories));
    }

    private Map<String, Double> totalsByCategory(Long accountId, DateRange range) {
        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (CategoryTotal total : computeCategoryTotals(accountId, range)) {
            byCategory.put(total.category(), total.total());
        }
        return byCategory;
    }

    /** Null rather than an infinite or made-up percentage when there was nothing to grow from. */
    private static Double changePercent(double current, double previous) {
        if (previous == 0) return null;
        return round2(((current - previous) / previous) * 100.0);
    }

    public List<MonthlyTotal> computeMonthlyTotals(Long accountId, DateRange range) {
        return computeMonthlyTotals(accountId, range, null);
    }

    public List<MonthlyTotal> computeMonthlyTotals(Long accountId, DateRange range, String currency) {
        String sql = "SELECT strftime('%Y-%m', t.date) AS month, ROUND(SUM(t.amount), 2) AS total "
                + SPEND_FILTER + filters(accountId, range, currency)
                + " GROUP BY month ORDER BY month";

        return jdbc.query(sql, params(accountId, range, currency), (rs, rowNum) ->
                new MonthlyTotal(rs.getString("month"), rs.getDouble("total")));
    }

    public List<CategoryMonthlySeries> computeMonthlyCategorySeries(Long accountId, DateRange range) {
        record Row(String date, String category, double amount) {}

        String sql = "SELECT t.date, t.category, t.amount "
                + SPEND_FILTER + " AND t.category IS NOT NULL" + filters(accountId, range, null);

        List<Row> rows = jdbc.query(sql, params(accountId, range, null), (rs, rowNum) ->
                new Row(rs.getString("date"), rs.getString("category"), rs.getDouble("amount")));

        Map<String, TreeMap<String, Double>> byCategory = new LinkedHashMap<>();
        for (Row row : rows) {
            String monthKey = row.date().substring(0, 7);
            byCategory
                    .computeIfAbsent(row.category(), k -> new TreeMap<>())
                    .merge(monthKey, row.amount(), Double::sum);
        }

        List<CategoryMonthlySeries> series = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            TreeMap<String, Double> monthMap = entry.getValue();

            List<MonthlyTotal> months = new ArrayList<>();
            List<Double> totals = new ArrayList<>();
            for (var monthEntry : monthMap.entrySet()) {
                double rounded = round2(monthEntry.getValue());
                months.add(new MonthlyTotal(monthEntry.getKey(), rounded));
                totals.add(rounded);
            }

            List<Double> last3 = totals.subList(Math.max(0, totals.size() - 3), totals.size());
            double movingAverage3mo = last3.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double overallTotal = totals.stream().mapToDouble(Double::doubleValue).sum();
            double lastMonthTotal = totals.isEmpty() ? 0 : totals.get(totals.size() - 1);

            series.add(new CategoryMonthlySeries(
                    entry.getKey(),
                    months,
                    round2(linearRegressionNext(totals)),
                    round2(movingAverage3mo),
                    round2(overallTotal),
                    lastMonthTotal
            ));
        }

        series.sort((a, b) -> Double.compare(b.overallTotal(), a.overallTotal()));
        return series;
    }

    /** Earliest and latest transaction dates on record, so the UI can bound its pickers. */
    public DateRange availableRange(Long accountId) {
        String sql = "SELECT MIN(t.date) AS min_date, MAX(t.date) AS max_date "
                + SPEND_FILTER + filters(accountId, DateRange.ALL, null);
        return jdbc.query(sql, params(accountId, DateRange.ALL, null), (rs, rowNum) ->
                        new DateRange(rs.getString("min_date"), rs.getString("max_date")))
                .stream().findFirst().orElse(DateRange.ALL);
    }

    /** Projects the next value of a series by least-squares fit. Package-private for testing. */
    static double linearRegressionNext(List<Double> points) {
        int n = points.size();
        if (n == 0) return 0;
        if (n == 1) return points.get(0);

        double meanX = (n - 1) / 2.0;
        double meanY = points.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - meanX) * (points.get(i) - meanY);
            den += (i - meanX) * (i - meanX);
        }
        double slope = den == 0 ? 0 : num / den;
        double intercept = meanY - slope * meanX;
        return Math.max(0, intercept + slope * n);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
