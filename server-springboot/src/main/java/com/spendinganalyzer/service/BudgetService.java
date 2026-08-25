package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.repository.BudgetRepository;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compares each category's monthly target against what was actually spent. */
@Service
public class BudgetService {

    /** Past this share of the limit a budget is worth warning about but has not been broken. */
    static final double NEAR_LIMIT_PERCENT = 80;

    private final BudgetRepository budgets;
    private final StatsService stats;

    public BudgetService(BudgetRepository budgets, StatsService stats) {
        this.budgets = budgets;
        this.stats = stats;
    }

    public record BudgetSummary(
            String month,
            List<BudgetProgress> budgets,
            double totalLimit,
            double totalSpent
    ) {}

    public BudgetSummary progress(Long accountId, String requestedMonth) {
        String month = resolveMonth(accountId, requestedMonth);

        // Reuses the dashboard's own spend query, so a budget counts exactly what the category
        // chart counts — income and transfer categories excluded, same account filter.
        Map<String, Double> spent = new HashMap<>();
        for (CategoryTotal total : stats.computeCategoryTotals(accountId, monthRange(month))) {
            spent.put(total.category(), total.total());
        }

        List<BudgetProgress> rows = new ArrayList<>();
        double totalLimit = 0;
        double totalSpent = 0;

        for (Budget budget : budgets.findAll()) {
            double used = spent.getOrDefault(budget.category(), 0.0);
            double percent = (used / budget.monthlyLimit()) * 100;

            rows.add(new BudgetProgress(
                    budget.id(),
                    budget.category(),
                    budget.monthlyLimit(),
                    round2(used),
                    round2(budget.monthlyLimit() - used),
                    round2(percent),
                    status(percent)
            ));

            totalLimit += budget.monthlyLimit();
            totalSpent += used;
        }

        return new BudgetSummary(month, rows, round2(totalLimit), round2(totalSpent));
    }

    private static String status(double percentUsed) {
        if (percentUsed > 100) return "over";
        if (percentUsed >= NEAR_LIMIT_PERCENT) return "near";
        return "under";
    }

    /**
     * Which month to measure. Statements are usually imported well after the fact, so defaulting
     * to the current calendar month would leave every budget showing zero spent on a fresh
     * import. The newest month on record is what the dashboard already calls "last month", and
     * it is the month a user comparing against a budget actually means.
     */
    private String resolveMonth(Long accountId, String requested) {
        if (requested != null && !requested.isBlank()) return validate(requested);

        String latest = stats.availableRange(accountId).to();
        return latest != null ? latest.substring(0, 7) : YearMonth.now().toString();
    }

    private static String validate(String month) {
        try {
            return YearMonth.parse(month.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'month' must be in YYYY-MM form, got: " + month);
        }
    }

    /** A month as an inclusive day range, so the existing date-range filters can be reused. */
    private static DateRange monthRange(String month) {
        YearMonth ym = YearMonth.parse(month);
        return new DateRange(ym.atDay(1).toString(), ym.atEndOfMonth().toString());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
