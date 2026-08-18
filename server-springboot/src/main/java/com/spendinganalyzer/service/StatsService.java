package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CategoryTotal> computeCategoryTotals() {
        return jdbc.query(
                """
                SELECT COALESCE(category, 'Uncategorized') as category, ROUND(SUM(amount), 2) as total, COUNT(*) as count
                FROM transactions
                WHERE type = 'debit'
                GROUP BY category ORDER BY total DESC
                """,
                (rs, rowNum) -> new CategoryTotal(rs.getString("category"), rs.getDouble("total"), rs.getInt("count"))
        );
    }

    public List<MonthlyTotal> computeMonthlyTotals() {
        return jdbc.query(
                """
                SELECT strftime('%Y-%m', date) as month, ROUND(SUM(amount), 2) as total
                FROM transactions
                WHERE type = 'debit' AND (category IS NULL OR category != 'Transfer')
                GROUP BY month ORDER BY month
                """,
                (rs, rowNum) -> new MonthlyTotal(rs.getString("month"), rs.getDouble("total"))
        );
    }

    public List<CategoryMonthlySeries> computeMonthlyCategorySeries() {
        record Row(String date, String category, double amount) {}

        List<Row> rows = jdbc.query(
                """
                SELECT date, category, amount FROM transactions
                WHERE type = 'debit' AND category IS NOT NULL AND category != 'Income' AND category != 'Transfer'
                """,
                (rs, rowNum) -> new Row(rs.getString("date"), rs.getString("category"), rs.getDouble("amount"))
        );

        Map<String, TreeMap<String, Double>> byCategory = new LinkedHashMap<>();
        for (Row row : rows) {
            String monthKey = row.date().substring(0, 7);
            byCategory
                    .computeIfAbsent(row.category(), k -> new TreeMap<>())
                    .merge(monthKey, row.amount(), Double::sum);
        }

        List<CategoryMonthlySeries> series = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            String category = entry.getKey();
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
                    category,
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

    private static double linearRegressionNext(List<Double> points) {
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
        double predicted = intercept + slope * n;
        return Math.max(0, predicted);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
