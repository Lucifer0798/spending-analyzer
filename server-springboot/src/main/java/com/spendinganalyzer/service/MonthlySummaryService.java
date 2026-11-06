package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.repository.BudgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the last complete calendar month went, worked out on read from what's already recorded: spend
 * against the month before, the biggest change by category, monthly budgets kept, and challenges that
 * ended in it. Follows the account filter but not the date filter -- it's about one fixed month.
 *
 * <p>It says what it can't vouch for rather than presenting a shaky comparison as fact: {@code
 * newestTransaction} is set when nothing newer than part-way through the month is on record, so the
 * month may not be fully imported; {@code dataStarts} when the record begins part-way through the
 * month before, so that month may read low. A budget set after the month ended isn't counted as kept
 * or broken: no one was holding to it then. Weekly and quarterly budgets aren't counted either, as
 * they don't measure that month.
 *
 * <p>Not available across accounts in more than one currency, or for a month with no spend.
 */
@Service
public class MonthlySummaryService {

    /** A record starting within this many days of the month's start counts as covering all of it. */
    static final int START_SLACK_DAYS = 6;

    /** One category across the two months; {@code change} is {@code total - previous}. */
    public record CategoryChange(String category, double total, double previous, double change) {}

    /**
     * @param previousTotal  spend the month before; null with none on record, and so are the changes
     * @param changePercent  of {@code previousTotal}
     * @param biggestRise    null when no category went up
     * @param biggestFall    null when no category went down
     * @param budgetsOver    the categories over budget, of the {@code budgetsTotal} counted
     * @param challengesKept and {@code challengesBroken}: those whose end date falls in the month
     */
    public record Summary(boolean available, String month, String monthStart, String monthEnd, String previousMonth,
                          String currency, double total, Double previousTotal, Double change, Double changePercent,
                          CategoryChange biggestRise, CategoryChange biggestFall, int budgetsKept, int budgetsTotal,
                          List<String> budgetsOver, int challengesKept, int challengesBroken,
                          String newestTransaction, String dataStarts) {

        static Summary unavailable() {
            return new Summary(false, null, null, null, null, null, 0, null, null, null, null, null, 0, 0, List.of(), 0, 0,
                    null, null);
        }
    }

    private final StatsService stats;
    private final BudgetService budgets;
    private final BudgetRepository budgetRepository;
    private final ChallengeService challenges;
    private final Clock clock;

    @Autowired
    public MonthlySummaryService(StatsService stats, BudgetService budgets, BudgetRepository budgetRepository,
                                 ChallengeService challenges) {
        this(stats, budgets, budgetRepository, challenges, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today". */
    MonthlySummaryService(StatsService stats, BudgetService budgets, BudgetRepository budgetRepository,
                          ChallengeService challenges, Clock clock) {
        this.stats = stats;
        this.budgets = budgets;
        this.budgetRepository = budgetRepository;
        this.challenges = challenges;
        this.clock = clock;
    }

    public Summary lastMonth(Long accountId) {
        String currency = stats.resolveCurrency(accountId);
        if (currency == null) return Summary.unavailable();

        YearMonth month = YearMonth.now(clock).minusMonths(1);
        YearMonth before = month.minusMonths(1);
        Map<String, Double> now = totals(accountId, month, currency);
        if (now.isEmpty()) return Summary.unavailable();
        Map<String, Double> then = totals(accountId, before, currency);

        double total = sum(now);
        Double previousTotal = then.isEmpty() ? null : sum(then);
        Double change = previousTotal == null ? null : Money.round2(total - previousTotal);
        Double changePercent = previousTotal == null ? null : Money.round2((total - previousTotal) / previousTotal * 100);

        CategoryChange rise = null;
        CategoryChange fall = null;
        // Against an empty month before, every category would "rise" -- that says nothing.
        if (previousTotal != null) {
            List<CategoryChange> changes = changes(now, then);
            rise = changes.stream().filter(c -> c.change() > 0)
                    .max(Comparator.comparingDouble(CategoryChange::change)).orElse(null);
            fall = changes.stream().filter(c -> c.change() < 0)
                    .min(Comparator.comparingDouble(CategoryChange::change)).orElse(null);
        }

        // Budgets: the monthly ones that already existed when the month ended.
        Set<String> setLater = new HashSet<>(budgetRepository.categoriesCreatedAfter(month.atEndOfMonth().toString()));
        List<BudgetProgress> counted = budgets.progress(accountId, month.toString()).budgets().stream()
                .filter(b -> Budget.MONTHLY.equals(b.period()) && !setLater.contains(b.category()))
                .toList();
        List<String> over = counted.stream().filter(b -> "over".equals(b.status())).map(BudgetProgress::category).toList();

        int kept = 0;
        int broken = 0;
        for (ChallengeService.Progress p : challenges.all()) {
            if (!YearMonth.from(LocalDate.parse(p.challenge().endDate())).equals(month)) continue;
            if (p.status() == ChallengeService.Status.COMPLETED) kept++;
            else if (p.status() == ChallengeService.Status.BROKEN) broken++;
        }

        DateRange onRecord = stats.availableRange(accountId);
        String newest = onRecord.to() != null && onRecord.to().compareTo(month.atEndOfMonth().toString()) < 0
                ? onRecord.to() : null;
        String starts = onRecord.from() != null
                && onRecord.from().compareTo(before.atDay(1).plusDays(START_SLACK_DAYS).toString()) > 0
                && onRecord.from().compareTo(before.atEndOfMonth().toString()) <= 0
                ? onRecord.from() : null;

        return new Summary(true, month.toString(), month.atDay(1).toString(), month.atEndOfMonth().toString(),
                before.toString(), currency, total, previousTotal, change, changePercent, rise, fall,
                counted.size() - over.size(), counted.size(), over, kept, broken, newest, starts);
    }

    private Map<String, Double> totals(Long accountId, YearMonth month, String currency) {
        Map<String, Double> byCategory = new HashMap<>();
        DateRange range = new DateRange(month.atDay(1).toString(), month.atEndOfMonth().toString());
        for (CategoryTotal t : stats.computeCategoryTotals(accountId, range, currency)) {
            if (t.total() > 0) byCategory.put(t.category(), t.total());
        }
        return byCategory;
    }

    private static double sum(Map<String, Double> totals) {
        return Money.round2(totals.values().stream().mapToDouble(Double::doubleValue).sum());
    }

    /** Sorted by name first, so a tie in {@code max}/{@code min} always picks the same category. */
    private static List<CategoryChange> changes(Map<String, Double> now, Map<String, Double> then) {
        Set<String> all = new HashSet<>(now.keySet());
        all.addAll(then.keySet());
        List<CategoryChange> changes = new ArrayList<>();
        for (String category : all.stream().sorted().toList()) {
            double a = now.getOrDefault(category, 0.0);
            double b = then.getOrDefault(category, 0.0);
            changes.add(new CategoryChange(category, a, b, Money.round2(a - b)));
        }
        return changes;
    }
}
