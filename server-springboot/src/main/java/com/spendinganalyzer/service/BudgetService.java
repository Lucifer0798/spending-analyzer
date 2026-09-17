package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.repository.BudgetRepository;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
            double totalSpent,
            String currency,
            boolean mixedCurrencies
    ) {}

    public BudgetSummary progress(Long accountId, String requestedMonth) {
        String month = resolveMonth(accountId, requestedMonth);
        String currency = stats.resolveCurrency(accountId);

        // "All accounts" spanning more than one currency has no single unit for a target or a
        // total spent — a limit set once can't be compared against spend in two currencies at
        // once, so this asks for one account instead of showing a number that mixes them.
        if (currency == null) {
            return new BudgetSummary(month, List.of(), 0, 0, null, true);
        }

        // Reuses the dashboard's own spend query, so a budget counts exactly what the category
        // chart counts — income and transfer categories excluded, same account filter.
        Map<String, Double> spent = new HashMap<>();
        for (CategoryTotal total : stats.computeCategoryTotals(accountId, monthRange(month))) {
            spent.put(total.category(), total.total());
        }

        YearMonth measuredMonth = YearMonth.parse(month);
        List<BudgetProgress> rows = new ArrayList<>();
        double totalLimit = 0;
        double totalSpent = 0;

        for (Budget budget : budgets.findAll()) {
            double used = spent.getOrDefault(budget.category(), 0.0);
            double limit = effectiveLimit(budget, measuredMonth);
            double percent = (used / limit) * 100;

            rows.add(new BudgetProgress(
                    budget.id(),
                    budget.category(),
                    round2(limit),
                    budget.monthlyLimit(),
                    round2(used),
                    round2(limit - used),
                    round2(percent),
                    status(percent),
                    budget.escalationType(),
                    budget.escalationValue(),
                    budget.escalationFrequencyMonths(),
                    budget.escalationStartMonth()
            ));

            totalLimit += limit;
            totalSpent += used;
        }

        return new BudgetSummary(month, rows, round2(totalLimit), round2(totalSpent), currency, false);
    }

    /**
     * The limit actually in effect for {@code month}, applying the budget's escalation schedule
     * if it has one. A schedule increases the base target every {@code escalationFrequencyMonths}
     * months starting from {@code escalationStartMonth} -- by a flat amount ({@code fixed}) or a
     * compounding percentage ({@code percent}) of the base. Measuring a month before the schedule
     * starts reports the base target unchanged, the same as having no schedule at all.
     */
    static double effectiveLimit(Budget budget, YearMonth month) {
        if (budget.escalationType() == null) return budget.monthlyLimit();

        YearMonth start = YearMonth.parse(budget.escalationStartMonth());
        long monthsElapsed = ChronoUnit.MONTHS.between(start, month);
        if (monthsElapsed < 0) return budget.monthlyLimit();

        long periods = monthsElapsed / budget.escalationFrequencyMonths();
        return switch (budget.escalationType()) {
            case "percent" -> budget.monthlyLimit() * Math.pow(1 + budget.escalationValue() / 100, periods);
            default -> budget.monthlyLimit() + budget.escalationValue() * periods;
        };
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
