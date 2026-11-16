package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.YearReview;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A calendar year summarised from the same spend and income definitions the dashboard uses --
 * nothing here counts money differently, it only cuts it by year.
 */
@Service
public class YearReviewService {

    static final int TOP_N = 5;
    /** A record starting within this many days of a month's start covers that month. */
    static final int START_SLACK_DAYS = 6;

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
                        List.of(), null, null, List.of(), List.of(), null, 0, List.of(), null);
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
            months.add(new YearReview.Month(key, Money.round2(in), Money.round2(out)));
            income += in;
            spend += out;
            if (out > 0 && (biggest == null || out > biggest.total())) {
                biggest = new MonthlyTotal(key, Money.round2(out));
            }
        }

        double saved = income - spend;
        Double savingsRate = income > 0 ? Money.round2(saved / income * 100) : null;

        List<CategoryTotal> categories = stats.computeCategoryTotals(accountId, range);
        double spendTotal = spend;
        List<YearReview.CategoryShare> topCategories = categories.stream()
                .limit(TOP_N)
                .map(c -> new YearReview.CategoryShare(c.category(), c.total(), c.count(),
                        spendTotal > 0 ? Money.round2(c.total() / spendTotal * 100) : 0))
                .toList();

        List<MerchantTotal> merchants = stats.computeMerchantTotals(accountId, range, null);
        List<MerchantTotal> topMerchants = merchants.subList(0, Math.min(TOP_N, merchants.size()));

        Integer previousYear = available.contains(year - 1) ? year - 1 : null;
        List<CategoryComparison> changes = List.of();
        int previousMonthsWithData = 0;
        YearReview.YearComparison comparison = null;
        if (previousYear != null) {
            Map<String, Double> previousIncome = toMap(stats.computeMonthlyIncomeTotals(accountId, yearRange(previousYear), null));
            Map<String, Double> previousSpend = toMap(stats.computeMonthlyTotals(accountId, yearRange(previousYear)));
            previousMonthsWithData = monthsWithData(previousIncome, previousSpend);

            // Like for like: only the months both years have data for, so an October-to-date year
            // isn't set against a full twelve months.
            Set<Integer> thisMonths = monthNumbers(incomeByMonth, spendByMonth);
            Set<Integer> thoseMonths = monthNumbers(previousIncome, previousSpend);
            List<Integer> shared = thisMonths.stream().filter(thoseMonths::contains).sorted().toList();
            if (!shared.isEmpty()) {
                changes = categoryChanges(accountId, year, previousYear, shared);
                comparison = comparison(shared, shared.size() < Math.max(thisMonths.size(), thoseMonths.size()),
                        year, previousYear, incomeByMonth, spendByMonth, previousIncome, previousSpend);
            }
        }

        return new YearReview(
                true, year, available, currency,
                Money.round2(income), Money.round2(spend), Money.round2(saved), savingsRate,
                monthsWithData(incomeByMonth, spendByMonth),
                months, biggest, quietest(accountId, year, spendByMonth), topCategories, List.copyOf(topMerchants),
                previousYear, previousMonthsWithData, changes, comparison);
    }

    /** Every category's spend over {@code months} of each year, biggest increase first. */
    private List<CategoryComparison> categoryChanges(Long accountId, int year, int previousYear, List<Integer> months) {
        Map<String, Double> now = new HashMap<>();
        Map<String, Double> then = new HashMap<>();
        for (int m : months) {
            for (CategoryTotal t : stats.computeCategoryTotals(accountId, monthRange(year, m))) now.merge(t.category(), t.total(), Double::sum);
            for (CategoryTotal t : stats.computeCategoryTotals(accountId, monthRange(previousYear, m))) then.merge(t.category(), t.total(), Double::sum);
        }
        Set<String> all = new TreeSet<>(now.keySet());
        all.addAll(then.keySet());
        List<CategoryComparison> changes = new ArrayList<>();
        for (String category : all) {
            double a = Money.round2(now.getOrDefault(category, 0.0));
            double b = Money.round2(then.getOrDefault(category, 0.0));
            changes.add(new CategoryComparison(category, a, b, Money.round2(a - b),
                    b == 0 ? null : Money.round2((a - b) / b * 100)));
        }
        changes.sort((x, y) -> Double.compare(y.changeAmount(), x.changeAmount()));
        return changes;
    }

    private static YearReview.YearComparison comparison(List<Integer> months, boolean likeForLike, int year, int previousYear,
                                                        Map<String, Double> income, Map<String, Double> spend,
                                                        Map<String, Double> previousIncome, Map<String, Double> previousSpend) {
        double in = 0, out = 0, prevIn = 0, prevOut = 0;
        for (int m : months) {
            in += income.getOrDefault(monthKey(year, m), 0.0);
            out += spend.getOrDefault(monthKey(year, m), 0.0);
            prevIn += previousIncome.getOrDefault(monthKey(previousYear, m), 0.0);
            prevOut += previousSpend.getOrDefault(monthKey(previousYear, m), 0.0);
        }
        return new YearReview.YearComparison(months, likeForLike, Money.round2(out), Money.round2(prevOut),
                Money.round2(in), Money.round2(prevIn), Money.round2(in - out), Money.round2(prevIn - prevOut),
                in > 0 ? Money.round2((in - out) / in * 100) : null,
                prevIn > 0 ? Money.round2((prevIn - prevOut) / prevIn * 100) : null);
    }

    /**
     * The month with the least spend, of those with any -- leaving out a month only partly on record:
     * the one the record ends in, unless it ends on that month's last day, and the one it starts in,
     * when it starts more than {@link #START_SLACK_DAYS} days in. Null with fewer than two left.
     */
    private MonthlyTotal quietest(Long accountId, int year, Map<String, Double> spendByMonth) {
        DateRange onRecord = stats.availableRange(accountId);
        Set<String> partial = new HashSet<>();
        if (onRecord.to() != null) {
            LocalDate last = LocalDate.parse(onRecord.to());
            if (!last.equals(YearMonth.from(last).atEndOfMonth())) partial.add(YearMonth.from(last).toString());
        }
        if (onRecord.from() != null) {
            LocalDate first = LocalDate.parse(onRecord.from());
            if (first.getDayOfMonth() > START_SLACK_DAYS + 1) partial.add(YearMonth.from(first).toString());
        }
        List<MonthlyTotal> candidates = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            String key = monthKey(year, m);
            double out = spendByMonth.getOrDefault(key, 0.0);
            if (out > 0 && !partial.contains(key)) candidates.add(new MonthlyTotal(key, Money.round2(out)));
        }
        if (candidates.size() < 2) return null;
        return candidates.stream().min(Comparator.comparingDouble(MonthlyTotal::total)).orElseThrow();
    }

    /** Month numbers (1-12) with any income or spend. */
    private static Set<Integer> monthNumbers(Map<String, Double> income, Map<String, Double> spend) {
        Set<Integer> months = new HashSet<>();
        for (String key : income.keySet()) months.add(Integer.parseInt(key.substring(5, 7)));
        for (String key : spend.keySet()) months.add(Integer.parseInt(key.substring(5, 7)));
        return months;
    }

    private static String monthKey(int year, int month) {
        return String.format("%d-%02d", year, month);
    }

    private static DateRange monthRange(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return new DateRange(ym.atDay(1).toString(), ym.atEndOfMonth().toString());
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

}
