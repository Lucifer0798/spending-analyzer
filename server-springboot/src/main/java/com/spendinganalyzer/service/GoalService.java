package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.GoalFundingPlan;
import com.spendinganalyzer.dto.GoalFundingSuggestion;
import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.model.GoalContribution;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalContributionRepository.GoalTotals;
import com.spendinganalyzer.repository.GoalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Measures each goal against what has actually been logged toward it. */
@Service
public class GoalService {

    /** For converting a $/day pace into a $/month one without assuming a fixed-length month. */
    private static final double AVG_DAYS_PER_MONTH = 30.44;

    /** How many of the most recent months with data the funding plan averages surplus over. */
    static final int SURPLUS_MONTHS = 3;

    private final GoalRepository goals;
    private final GoalContributionRepository contributions;
    private final StatsService stats;
    private final Clock clock;

    @Autowired
    public GoalService(GoalRepository goals, GoalContributionRepository contributions, StatsService stats) {
        this(goals, contributions, stats, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today" instead of asserting against a moving target. */
    GoalService(GoalRepository goals, GoalContributionRepository contributions, StatsService stats, Clock clock) {
        this.goals = goals;
        this.contributions = contributions;
        this.stats = stats;
        this.clock = clock;
    }

    public List<GoalProgress> progress() {
        Map<Long, GoalTotals> totals = contributions.totalsByGoal();
        List<GoalProgress> rows = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        for (Goal goal : goals.findAll()) {
            GoalTotals t = totals.getOrDefault(goal.id(), GoalTotals.NONE);
            double percent = (t.sum() / goal.targetAmount()) * 100;
            boolean achieved = t.sum() >= goal.targetAmount();

            double monthlyPace = monthlyPace(t, today);
            String projectedDate = null;
            if (t.firstContributionDate() != null) {
                if (!achieved && monthlyPace > 0) {
                    double remaining = goal.targetAmount() - t.sum();
                    long daysToGo = Math.round((remaining / monthlyPace) * AVG_DAYS_PER_MONTH);
                    projectedDate = today.plusDays(daysToGo).toString();
                }
            }

            rows.add(new GoalProgress(
                    goal.id(),
                    goal.name(),
                    goal.targetAmount(),
                    goal.targetDate(),
                    goal.currency(),
                    round2(t.sum()),
                    round2(Math.max(0, goal.targetAmount() - t.sum())),
                    round2(percent),
                    achieved,
                    t.count(),
                    monthlyPace,
                    projectedDate
            ));
        }
        return rows;
    }

    /**
     * A suggested monthly contribution for every open goal, one plan per goal currency -- goals in
     * different currencies are never priced against the same surplus.
     *
     * <p>Dated goals come first: each needs its remaining amount spread over the months left until
     * its target date, and gets exactly that whether or not the surplus can pay for it -- shrinking
     * a deadline's figure to fit would just be a quieter way of missing it, so a shortfall is
     * reported as a shortfall instead. Whatever surplus is left after every deadline is split
     * evenly across the goals with no date.
     *
     * <p>Deadlines are measured from today's real date, but surplus from the newest months that
     * actually have transactions -- statements are imported after the fact, the same reason
     * budgets anchor to the latest transaction rather than the calendar.
     */
    public List<GoalFundingPlan> fundingPlan() {
        Map<Long, GoalTotals> totals = contributions.totalsByGoal();
        LocalDate today = LocalDate.now(clock);

        Map<String, List<Goal>> openByCurrency = new LinkedHashMap<>();
        for (Goal goal : goals.findAll()) {
            double saved = totals.getOrDefault(goal.id(), GoalTotals.NONE).sum();
            if (saved < goal.targetAmount()) {
                openByCurrency.computeIfAbsent(goal.currency(), k -> new ArrayList<>()).add(goal);
            }
        }

        List<GoalFundingPlan> plans = new ArrayList<>();
        for (var entry : openByCurrency.entrySet()) {
            plans.add(planFor(entry.getKey(), entry.getValue(), totals, today));
        }
        return plans;
    }

    private GoalFundingPlan planFor(String currency, List<Goal> open, Map<Long, GoalTotals> totals, LocalDate today) {
        Surplus surplus = surplus(currency);

        double totalRequired = 0;
        Map<Long, Double> required = new LinkedHashMap<>();
        for (Goal goal : open) {
            if (goal.targetDate() == null) continue;
            double remaining = goal.targetAmount() - totals.getOrDefault(goal.id(), GoalTotals.NONE).sum();
            // At least one month: a deadline this month, or already past, needs the whole
            // remainder now rather than a figure inflated by dividing by a fraction of a month.
            double monthsLeft = Math.max(1.0,
                    ChronoUnit.DAYS.between(today, LocalDate.parse(goal.targetDate())) / AVG_DAYS_PER_MONTH);
            double perMonth = remaining / monthsLeft;
            required.put(goal.id(), perMonth);
            totalRequired += perMonth;
        }

        Double leftover = surplus == null ? null : surplus.average() - totalRequired;
        long undatedCount = open.stream().filter(g -> g.targetDate() == null).count();
        Double undatedShare = leftover != null && leftover > 0 && undatedCount > 0 ? leftover / undatedCount : null;

        List<GoalFundingSuggestion> suggestions = new ArrayList<>();
        for (Goal goal : open) {
            GoalTotals t = totals.getOrDefault(goal.id(), GoalTotals.NONE);
            double remaining = goal.targetAmount() - t.sum();

            Double suggested;
            String basis;
            String projected = null;
            if (goal.targetDate() != null) {
                suggested = round2(required.get(goal.id()));
                basis = "deadline";
            } else if (undatedShare != null) {
                suggested = round2(undatedShare);
                basis = "surplus";
                projected = today.plusDays(Math.round((remaining / undatedShare) * AVG_DAYS_PER_MONTH)).toString();
            } else {
                suggested = null;
                basis = "unfunded";
            }

            suggestions.add(new GoalFundingSuggestion(
                    goal.id(), goal.name(), round2(remaining), goal.targetDate(),
                    suggested, basis, monthlyPace(t, today), projected));
        }

        return new GoalFundingPlan(
                currency,
                surplus == null ? null : round2(surplus.average()),
                surplus == null ? 0 : surplus.months(),
                round2(totalRequired),
                leftover == null ? null : leftover >= 0,
                leftover == null ? null : round2(leftover),
                suggestions);
    }

    private record Surplus(double average, int months) {}

    /**
     * Average monthly income minus spend across every account in {@code currency}, over the most
     * recent {@link #SURPLUS_MONTHS} months that have any transactions at all -- a month with no
     * data is a gap in what was imported, not a month where nothing was earned or spent, so it
     * isn't averaged in as zero. Null when the currency has no transactions.
     */
    private Surplus surplus(String currency) {
        TreeMap<String, Double> byMonth = new TreeMap<>();
        for (MonthlyTotal income : stats.computeMonthlyIncomeTotals(null, DateRange.ALL, currency)) {
            byMonth.merge(income.month(), income.total(), Double::sum);
        }
        for (MonthlyTotal spend : stats.computeMonthlyTotals(null, DateRange.ALL, currency)) {
            byMonth.merge(spend.month(), -spend.total(), Double::sum);
        }
        if (byMonth.isEmpty()) return null;

        List<Double> recent = new ArrayList<>(byMonth.descendingMap().values())
                .subList(0, Math.min(SURPLUS_MONTHS, byMonth.size()));
        double average = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Surplus(average, recent.size());
    }

    /** One goal's share of a single real-world contribution split across several goals. */
    public record SplitEntry(long goalId, double amount) {}

    /**
     * Logs one contribution per entry, sharing the same date and note, as a single atomic
     * operation -- if inserting one entry fails partway through, none of them stick. Each
     * resulting row is otherwise an ordinary, independent {@code goal_contributions} row; there
     * is no separate "this was one split event" record; a shared note is the only thing tying
     * them together for a human reading the history back later, and each can be individually
     * removed like any other contribution if one goal's share needs correcting.
     *
     * <p>Every {@code goalId} is assumed already validated to exist -- this schema has no foreign
     * key to catch a bad one, and the controller checks each one before calling this, the same
     * division of responsibility {@code BudgetController} keeps from {@code BudgetRepository}.
     */
    @Transactional
    public List<GoalContribution> addSplitContributions(List<SplitEntry> splits, String date, String note) {
        List<GoalContribution> created = new ArrayList<>();
        for (SplitEntry entry : splits) {
            created.add(contributions.add(entry.goalId(), entry.amount(), date, note));
        }
        return created;
    }

    /**
     * The average net contribution rate since the first one logged, per 30.44-day month; zero
     * with nothing logged. Measured from the very first contribution, not a recent window: a
     * handful of logged amounts is too little history to average over a trailing period the way
     * spend forecasting does with months of transactions.
     *
     * <p>The elapsed time is floored at one whole month, so everything logged within the first
     * month reads as that much per month -- a contribution logged today is "$X this month", not
     * $X divided by a single day and scaled up thirty-fold.
     */
    private static double monthlyPace(GoalTotals t, LocalDate today) {
        if (t.firstContributionDate() == null) return 0;
        double daysElapsed = Math.max(AVG_DAYS_PER_MONTH,
                ChronoUnit.DAYS.between(LocalDate.parse(t.firstContributionDate()), today));
        return round2((t.sum() / daysElapsed) * AVG_DAYS_PER_MONTH);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
