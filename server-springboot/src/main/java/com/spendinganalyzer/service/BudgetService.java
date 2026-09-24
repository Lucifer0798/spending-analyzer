package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.repository.BudgetRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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

        YearMonth measuredMonth = YearMonth.parse(month);
        // Every budget sharing the same measured range (almost always true for the common case of
        // several monthly budgets) reuses one query instead of repeating it per budget.
        Map<DateRange, Map<String, Double>> spendByRange = new HashMap<>();
        // Rollover re-derives every prior month's spend for a category since its start month, so
        // budgets sharing a history of months would otherwise repeat that query once per budget.
        // Memoized here, scoped to one progress() call, rather than as a field: there is no
        // reason for one request's history to leak into or stale-cache across another's.
        Map<YearMonth, Map<String, Double>> spendByMonth = new HashMap<>();
        List<BudgetProgress> rows = new ArrayList<>();
        double totalLimit = 0;
        double totalSpent = 0;

        for (Budget budget : budgets.findAll()) {
            boolean monthly = Budget.MONTHLY.equals(budget.period());
            DateRange periodRange = periodRange(budget, measuredMonth);
            Map<String, Double> spentInRange =
                    spendByRange.computeIfAbsent(periodRange, r -> spendByCategory(accountId, r));
            double used = spentInRange.getOrDefault(budget.category(), 0.0);

            // Escalation and rollover are both defined in whole months, so neither applies once a
            // budget measures a week or a quarter instead — the raw target is the whole limit.
            double carryIn = monthly ? rolloverCarryIn(accountId, budget, measuredMonth, spendByMonth) : 0.0;
            double limit = (monthly ? effectiveLimit(budget, measuredMonth) : budget.monthlyLimit()) + carryIn;
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
                    budget.escalationStartMonth(),
                    budget.rolloverStartMonth(),
                    round2(carryIn),
                    budget.period(),
                    periodRange.from(),
                    periodRange.to()
            ));

            // A week's or a quarter's own target is not the same unit as "this month" -- adding it
            // into the combined total would overstate (a quarterly insurance premium counted as if
            // due every month) or understate (a weekly allowance counted only once) what the shared
            // total claims to represent. Only budgets that actually share the measured month
            // contribute to it, the same reasoning a mixed-currency "all accounts" total is refused
            // rather than summed.
            if (monthly) {
                totalLimit += limit;
                totalSpent += used;
            }
        }

        return new BudgetSummary(month, rows, round2(totalLimit), round2(totalSpent), currency, false);
    }

    /**
     * The date range a budget's spend is actually measured over for {@code anchorMonth} -- the
     * month itself for a monthly budget, or the quarter/week containing it otherwise. Every budget
     * on the page shares the same anchor month (there is only one "which month" control), so a
     * weekly or quarterly budget's range still moves in step with everything else when that
     * control changes, rather than tracking today's date independently.
     */
    private static DateRange periodRange(Budget budget, YearMonth anchorMonth) {
        return switch (budget.period()) {
            case Budget.WEEKLY -> weekRange(anchorMonth.atEndOfMonth());
            case Budget.QUARTERLY -> quarterRange(anchorMonth);
            default -> monthRange(anchorMonth.toString());
        };
    }

    /** The Monday-to-Sunday ISO week containing {@code anchorDate}. */
    private static DateRange weekRange(LocalDate anchorDate) {
        LocalDate monday = anchorDate.minusDays(anchorDate.getDayOfWeek().getValue() - 1L);
        return new DateRange(monday.toString(), monday.plusDays(6).toString());
    }

    /** The calendar quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec) containing {@code anchorMonth}. */
    private static DateRange quarterRange(YearMonth anchorMonth) {
        int quarterStartMonthNum = ((anchorMonth.getMonthValue() - 1) / 3) * 3 + 1;
        YearMonth quarterStart = YearMonth.of(anchorMonth.getYear(), quarterStartMonthNum);
        return new DateRange(quarterStart.atDay(1).toString(), quarterStart.plusMonths(2).atEndOfMonth().toString());
    }

    /**
     * Unused budget (or overspend, negative) carried in from every month between {@code
     * rolloverStartMonth} and {@code month}, exclusive of {@code month} itself. Each historical
     * month's own contribution is {@code effectiveLimit(that month) - actualSpend(that month)} --
     * escalation and rollover compose for free this way, since a historical month's limit already
     * accounts for its own escalation, and the loop simply keeps walking forward one month at a
     * time from the start. Measuring the start month itself (or anything before it) carries in
     * nothing, the same "no schedule yet" honesty {@code effectiveLimit} already applies to a
     * month before an escalation schedule starts.
     */
    private double rolloverCarryIn(
            Long accountId, Budget budget, YearMonth month, Map<YearMonth, Map<String, Double>> spendByMonth
    ) {
        if (budget.rolloverStartMonth() == null) return 0.0;

        YearMonth start = YearMonth.parse(budget.rolloverStartMonth());
        double carry = 0.0;
        for (YearMonth m = start; m.isBefore(month); m = m.plusMonths(1)) {
            Map<String, Double> spendThatMonth =
                    spendByMonth.computeIfAbsent(m, k -> spendByCategory(accountId, monthRange(k.toString())));
            double spentThatMonth = spendThatMonth.getOrDefault(budget.category(), 0.0);
            carry += effectiveLimit(budget, m) - spentThatMonth;
        }
        return carry;
    }

    private Map<String, Double> spendByCategory(Long accountId, DateRange range) {
        Map<String, Double> byCategory = new HashMap<>();
        for (CategoryTotal total : stats.computeCategoryTotals(accountId, range)) {
            byCategory.put(total.category(), total.total());
        }
        return byCategory;
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
