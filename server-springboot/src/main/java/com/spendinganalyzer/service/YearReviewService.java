package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PeriodComparison;
import com.spendinganalyzer.dto.YearReview;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A calendar year summarised from the same spend and income definitions the dashboard uses --
 * nothing here counts money differently, it only cuts it by year.
 */
@Service
public class YearReviewService {

    static final int TOP_N = 5;

    private final StatsService stats;

    public YearReviewService(StatsService stats) {
        this.stats = stats;
    }

    /**
     * @param year null for the newest year with data -- statements are imported after the fact,
     *             so the calendar's current year may well be empty
     */
    public YearReview review(Long accountId, Integer year) {
        List<Integer> available = stats.availableYears(accountId);
        String currency = stats.resolveCurrency(accountId);
        if (currency == null) {
            return YearReview.notApplicable(available);
        }
        if (year == null) {
            if (available.isEmpty()) {
                return new YearReview(true, null, available, currency, 0, 0, 0, null, 0,
                        List.of(), null, List.of(), List.of(), null, 0, List.of());
            }
            year = available.get(available.size() - 1);
        }

        DateRange range = yearRange(year);
        Map<String, Double> incomeByMonth = toMap(stats.computeMonthlyIncomeTotals(accountId, range, null));
        Map<String, Double> spendByMonth = toMap(stats.computeMonthlyTotals(accountId, range));

        List<YearReview.Month> months = new ArrayList<>();
        MonthlyTotal biggest = null;
        double income = 0;
        double spend = 0;
        for (int m = 1; m <= 12; m++) {
            String key = String.format("%d-%02d", year, m);
            double in = incomeByMonth.getOrDefault(key, 0.0);
            double out = spendByMonth.getOrDefault(key, 0.0);
            months.add(new YearReview.Month(key, round2(in), round2(out)));
            income += in;
            spend += out;
            if (out > 0 && (biggest == null || out > biggest.total())) {
                biggest = new MonthlyTotal(key, round2(out));
            }
        }

        double saved = income - spend;
        Double savingsRate = income > 0 ? round2(saved / income * 100) : null;

        List<CategoryTotal> categories = stats.computeCategoryTotals(accountId, range);
        double spendTotal = spend;
        List<YearReview.CategoryShare> topCategories = categories.stream()
                .limit(TOP_N)
                .map(c -> new YearReview.CategoryShare(c.category(), c.total(), c.count(),
                        spendTotal > 0 ? round2(c.total() / spendTotal * 100) : 0))
                .toList();

        List<MerchantTotal> merchants = stats.computeMerchantTotals(accountId, range, null);
        List<MerchantTotal> topMerchants = merchants.subList(0, Math.min(TOP_N, merchants.size()));

        Integer previousYear = available.contains(year - 1) ? year - 1 : null;
        List<CategoryComparison> changes = List.of();
        int previousMonthsWithData = 0;
        if (previousYear != null) {
            DateRange previousRange = yearRange(previousYear);
            changes = stats.computeComparison(accountId, range, previousRange)
                    .map(PeriodComparison::categories)
                    .orElse(List.of());
            previousMonthsWithData = monthsWithData(accountId, previousRange);
        }

        return new YearReview(
                true, year, available, currency,
                round2(income), round2(spend), round2(saved), savingsRate,
                monthsWithData(incomeByMonth, spendByMonth),
                months, biggest, topCategories, List.copyOf(topMerchants),
                previousYear, previousMonthsWithData, changes);
    }

    private int monthsWithData(Long accountId, DateRange range) {
        return monthsWithData(
                toMap(stats.computeMonthlyIncomeTotals(accountId, range, null)),
                toMap(stats.computeMonthlyTotals(accountId, range)));
    }

    /** Months with any income or spend -- transfers alone don't make a month "imported". */
    private static int monthsWithData(Map<String, Double> income, Map<String, Double> spend) {
        Set<String> months = new HashSet<>(income.keySet());
        months.addAll(spend.keySet());
        return months.size();
    }

    private static DateRange yearRange(int year) {
        return new DateRange(year + "-01-01", year + "-12-31");
    }

    private static Map<String, Double> toMap(List<MonthlyTotal> totals) {
        Map<String, Double> byMonth = new HashMap<>();
        for (MonthlyTotal t : totals) byMonth.put(t.month(), t.total());
        return byMonth;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
