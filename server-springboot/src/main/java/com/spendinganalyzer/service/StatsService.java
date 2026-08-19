package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class StatsService {

    private final NamedParameterJdbcTemplate jdbc;

    public StatsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Spend excludes anything flagged as income or transfer. The flags live on the
     * categories table rather than being hardcoded names, so a user-created category
     * such as "Moving money to savings" can be excluded from spend totals too.
     */
    private static final String SPEND_FILTER = """
            FROM transactions t
            LEFT JOIN categories c ON c.name = t.category
            WHERE t.type = 'debit'
              AND COALESCE(c.is_income, 0) = 0
              AND COALESCE(c.is_transfer, 0) = 0
            """;

    private static MapSqlParameterSource accountParams(Long accountId) {
        return new MapSqlParameterSource("accountId", accountId);
    }

    private static String accountClause(Long accountId) {
        return accountId != null ? " AND t.account_id = :accountId" : "";
    }

    public List<CategoryTotal> computeCategoryTotals(Long accountId) {
        String sql = "SELECT COALESCE(t.category, 'Uncategorized') AS category, "
                + "ROUND(SUM(t.amount), 2) AS total, COUNT(*) AS count "
                + SPEND_FILTER + accountClause(accountId)
                + " GROUP BY t.category ORDER BY total DESC";

        return jdbc.query(sql, accountParams(accountId), (rs, rowNum) ->
                new CategoryTotal(rs.getString("category"), rs.getDouble("total"), rs.getInt("count")));
    }

    public List<MonthlyTotal> computeMonthlyTotals(Long accountId) {
        String sql = "SELECT strftime('%Y-%m', t.date) AS month, ROUND(SUM(t.amount), 2) AS total "
                + SPEND_FILTER + accountClause(accountId)
                + " GROUP BY month ORDER BY month";

        return jdbc.query(sql, accountParams(accountId), (rs, rowNum) ->
                new MonthlyTotal(rs.getString("month"), rs.getDouble("total")));
    }

    public List<CategoryMonthlySeries> computeMonthlyCategorySeries(Long accountId) {
        record Row(String date, String category, double amount) {}

        String sql = "SELECT t.date, t.category, t.amount "
                + SPEND_FILTER + " AND t.category IS NOT NULL" + accountClause(accountId);

        List<Row> rows = jdbc.query(sql, accountParams(accountId), (rs, rowNum) ->
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
