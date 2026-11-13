package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ready-made challenges worked out from your own spending, for the challenge form to start from.
 * The two category ideas only look at spending you choose: charges recurring detection recognises
 * as regular -- subscriptions, rent, bills -- are left out, since a week without a Netflix charge
 * isn't something you decide. The fortnight counts everything, as it's about the total.
 *
 * <ul>
 *   <li><b>No-spend week</b> for the category you buy from most often in small amounts -- at least
 *       {@link #FREQUENT_COUNT} purchases averaging no more than {@link #SMALL_PURCHASE} -- the coffees
 *       and takeaways a week off is realistic for. Rent and bills never qualify.</li>
 *   <li><b>Halve it for 30 days</b> for the biggest other category bought from regularly (at least
 *       {@link #REGULAR_COUNT} purchases), capped at half its usual month.</li>
 *   <li><b>A cheaper fortnight</b> across all spending, at {@link #FORTNIGHT_SHARE} of a usual two weeks.</li>
 * </ul>
 *
 * "Usual" is the {@link #WINDOW_DAYS} days up to the newest spend on record, not up to today, so an
 * import that's a few weeks behind doesn't make every habit look smaller. A category that already has
 * a challenge running or about to start isn't suggested again. Amounts are rounded to whole units --
 * to fives from 20 up -- since a target is a feel, not a calculation.
 */
@Service
public class ChallengeTemplateService {

    static final int WINDOW_DAYS = 90;
    static final int FREQUENT_COUNT = 8;
    static final double SMALL_PURCHASE = 60;
    static final int REGULAR_COUNT = 6;
    static final double FORTNIGHT_SHARE = 0.85;
    /** How far back recurring detection looks: long enough for a monthly charge to show as a series. */
    static final int RECURRING_LOOKBACK_DAYS = 365;

    /**
     * @param category    null for all spending
     * @param limitAmount 0 for no spending
     * @param days        how long it runs, starting today
     * @param usual       what this scope usually costs over {@code days}
     * @param basis       one line on what it's worked out from, to show beside it
     */
    public record Template(String key, String title, String category, double limitAmount, int days, double usual,
                           String basis) {}

    private final StatsService stats;
    private final ChallengeService challenges;
    private final TransactionRepository transactions;
    private final RecurringDetectionService recurring;

    public ChallengeTemplateService(StatsService stats, ChallengeService challenges, TransactionRepository transactions,
                                    RecurringDetectionService recurring) {
        this.stats = stats;
        this.challenges = challenges;
        this.transactions = transactions;
        this.recurring = recurring;
    }

    public List<Template> templates(Long accountId) {
        String currency = stats.resolveCurrency(accountId);
        String newest = stats.availableRange(accountId).to();
        if (currency == null || newest == null) return List.of();

        LocalDate to = LocalDate.parse(newest);
        DateRange window = new DateRange(to.minusDays(WINDOW_DAYS - 1).toString(), to.toString());
        List<CategoryTotal> totals = stats.computeCategoryTotals(accountId, window, currency).stream()
                .filter(t -> t.category() != null && t.total() > 0)
                .toList();
        List<CategoryTotal> chosen = chosenSpending(accountId, window, to);

        Set<String> taken = new HashSet<>();
        for (ChallengeService.Progress p : challenges.all()) {
            boolean running = p.status() == ChallengeService.Status.ACTIVE || p.status() == ChallengeService.Status.UPCOMING;
            if (running && p.challenge().category() != null) taken.add(p.challenge().category());
        }

        List<Template> templates = new ArrayList<>();

        chosen.stream()
                .filter(t -> !taken.contains(t.category()) && t.count() >= FREQUENT_COUNT
                        && t.total() / t.count() <= SMALL_PURCHASE)
                .max(Comparator.comparingInt(CategoryTotal::count).thenComparing(CategoryTotal::total))
                .ifPresent(t -> {
                    double week = per(t.total(), 7);
                    templates.add(new Template("no-spend-week", "No " + t.category() + " for a week", t.category(), 0, 7,
                            week, t.count() + " purchases in " + WINDOW_DAYS + " days — about " + whole(week) + " a week"));
                    taken.add(t.category());
                });

        chosen.stream()
                .filter(t -> !taken.contains(t.category()) && t.count() >= REGULAR_COUNT)
                .max(Comparator.comparingDouble(CategoryTotal::total))
                .ifPresent(t -> {
                    double month = per(t.total(), 30);
                    templates.add(new Template("halve-category", "Halve your usual " + t.category() + " for 30 days",
                            t.category(), rounded(month / 2), 30, month, "Usually about " + whole(month) + " a month"));
                });

        double all = totals.stream().mapToDouble(CategoryTotal::total).sum();
        if (all > 0) {
            double fortnight = per(all, 14);
            templates.add(new Template("cheaper-fortnight", "A cheaper fortnight", null, rounded(fortnight * FORTNIGHT_SHARE),
                    14, fortnight, "Usually about " + whole(fortnight) + " every two weeks, all spending"));
        }
        return templates;
    }

    /**
     * Spend per category in the window, leaving out every charge from a merchant recurring detection
     * recognises over the year to {@code to}. A split counts your share, as everywhere.
     */
    private List<CategoryTotal> chosenSpending(Long accountId, DateRange window, LocalDate to) {
        DateRange year = new DateRange(to.minusDays(RECURRING_LOOKBACK_DAYS - 1).toString(), to.toString());
        Set<String> regular = recurring.detect(transactions.findSpendingTransactions(accountId, year)).stream()
                .map(RecurringSeries::merchant)
                .collect(Collectors.toSet());

        Map<String, double[]> byCategory = new LinkedHashMap<>();   // category -> {total, count}
        for (Transaction t : transactions.findSpendingTransactions(accountId, window)) {
            if (t.category() == null || regular.contains(MerchantNormalizer.normalize(t.description()))) continue;
            double[] acc = byCategory.computeIfAbsent(t.category(), k -> new double[2]);
            acc[0] += t.splitShare() != null ? t.splitShare() : t.amount();
            acc[1]++;
        }
        List<CategoryTotal> chosen = new ArrayList<>();
        byCategory.forEach((category, acc) -> chosen.add(new CategoryTotal(category, Money.round2(acc[0]), (int) acc[1])));
        return chosen;
    }

    /** {@code total} over the window, scaled to {@code days}. */
    private static double per(double total, int days) {
        return Money.round2(total / WINDOW_DAYS * days);
    }

    /** To whole units, and to fives from 20 up. */
    static double rounded(double amount) {
        if (amount < 20) return Math.max(1, Math.round(amount));
        return Math.round(amount / 5) * 5.0;
    }

    private static String whole(double amount) {
        return String.valueOf(Math.round(amount));
    }
}
