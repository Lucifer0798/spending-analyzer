package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.CategoryDetail;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PeriodComparison;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One category's drill-down, assembled from the same {@link StatsService} queries the dashboard
 * uses -- so its numbers always agree with the bar it was opened from.
 */
@Service
public class CategoryDetailService {

    static final int TOP_MERCHANTS = 8;
    static final int RECENT = 10;

    private final StatsService stats;
    private final TransactionRepository transactions;

    public CategoryDetailService(StatsService stats, TransactionRepository transactions) {
        this.stats = stats;
        this.transactions = transactions;
    }

    public CategoryDetail detail(String category, Long accountId, DateRange range) {
        String currency = stats.resolveCurrency(accountId);
        if (currency == null) {
            return CategoryDetail.notApplicable(category);
        }

        List<CategoryTotal> totals = stats.computeCategoryTotals(accountId, range);
        double allSpend = totals.stream().mapToDouble(CategoryTotal::total).sum();
        CategoryTotal mine = totals.stream().filter(t -> t.category().equals(category)).findFirst()
                .orElse(new CategoryTotal(category, 0, 0));

        // The timeline is every month with *any* spend in range, so a month this category skipped
        // shows as zero instead of vanishing -- the dashboard's monthly chart uses the same months.
        Map<String, Double> mineByMonth = new HashMap<>();
        for (MonthlyTotal m : stats.computeMonthlyTotalsForCategory(accountId, range, category)) {
            mineByMonth.put(m.month(), m.total());
        }
        List<MonthlyTotal> months = stats.computeMonthlyTotals(accountId, range).stream()
                .map(m -> new MonthlyTotal(m.month(), mineByMonth.getOrDefault(m.month(), 0.0)))
                .toList();
        double averagePerMonth = months.isEmpty() ? 0 : mine.total() / months.size();

        CategoryComparison change = stats.computeComparison(accountId, range)
                .map(PeriodComparison::categories)
                .flatMap(list -> list.stream().filter(c -> c.category().equals(category)).findFirst())
                .orElse(null);

        List<MerchantTotal> merchants = stats.computeMerchantTotals(accountId, range, null, category);

        return new CategoryDetail(
                true, category, currency,
                round2(mine.total()), mine.count(),
                allSpend > 0 ? round2(mine.total() / allSpend * 100) : 0,
                round2(averagePerMonth),
                months, change,
                List.copyOf(merchants.subList(0, Math.min(TOP_MERCHANTS, merchants.size()))),
                merchants.size(),
                transactions.find(category, null, accountId, range, RECENT, 0));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
