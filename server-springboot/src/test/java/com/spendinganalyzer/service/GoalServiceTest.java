package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.GoalFundingPlan;
import com.spendinganalyzer.dto.GoalFundingSuggestion;
import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoalServiceTest {

    @Autowired
    private GoalService service;

    @Autowired
    private GoalRepository goals;

    @Autowired
    private GoalContributionRepository contributions;

    @Autowired
    private StatsService stats;

    @Autowired
    private TransactionRepository transactions;

    /** A fixed instant so a pace projection can be asserted exactly instead of racing the clock. */
    private GoalService serviceAsOf(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new GoalService(goals, contributions, stats, clock);
    }

    @Test
    @DisplayName("a goal with no contributions shows zero saved, not achieved, and no pace to project from")
    void goalWithNoContributions() {
        goals.create("Emergency fund", 1000.0, null, "USD");

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.saved()).isEqualTo(0.0);
        assertThat(progress.remaining()).isEqualTo(1000.0);
        assertThat(progress.percentComplete()).isEqualTo(0.0);
        assertThat(progress.achieved()).isFalse();
        assertThat(progress.contributionCount()).isEqualTo(0);
        assertThat(progress.monthlyPace()).isEqualTo(0.0);
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("projects a completion date from the pace since the first contribution")
    void projectsCompletionDateFromPace() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 300.0, "2026-06-01", null);

        // $300 over 60 days (past the one-month floor, so it doesn't apply) is $5/day, i.e.
        // $152.20/month; $700 left to go at that rate takes exactly 140 days (the
        // 30.44-day-per-month conversion cancels out algebraically).
        LocalDate today = LocalDate.of(2026, 7, 31);
        GoalProgress progress = serviceAsOf(today).progress().get(0);

        assertThat(progress.monthlyPace()).isEqualTo(152.2);
        assertThat(progress.projectedCompletionDate()).isEqualTo(today.plusDays(140).toString());
    }

    @Test
    @DisplayName("an achieved goal has nothing left to project")
    void achievedGoalHasNoProjection() {
        long id = goals.create("Small target", 100.0, null, "USD").id();
        contributions.add(id, 150.0, "2026-06-01", null);

        LocalDate today = LocalDate.of(2026, 7, 1);
        GoalProgress progress = serviceAsOf(today).progress().get(0);

        assertThat(progress.achieved()).isTrue();
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("a net-negative pace has no projection, since it would never arrive")
    void negativePaceHasNoProjection() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 100.0, "2026-06-01", null);
        contributions.add(id, -150.0, "2026-06-15", "had to dip in");

        LocalDate today = LocalDate.of(2026, 7, 1);
        GoalProgress progress = serviceAsOf(today).progress().get(0);

        assertThat(progress.monthlyPace()).isLessThan(0);
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("a first contribution made today reads as that much per month, not one day's worth scaled up")
    void contributionMadeTodayCountsAsOneMonth() {
        long id = goals.create("Vacation", 2400.0, null, "USD").id();
        LocalDate today = LocalDate.of(2026, 7, 1);
        contributions.add(id, 800.0, today.toString(), null);

        GoalProgress progress = serviceAsOf(today).progress().get(0);

        // Not $800 / 1 day * 30.44 = $24,352/month: under a month of history is measured as a
        // whole month. $1,600 left at $800/month is two months, 60.88 days, rounded to 61.
        assertThat(progress.monthlyPace()).isEqualTo(800.0);
        assertThat(progress.projectedCompletionDate()).isEqualTo(today.plusDays(61).toString());
    }

    @Test
    @DisplayName("contributions within the first month still measure over a whole month")
    void contributionsWithinFirstMonthMeasuredOverAMonth() {
        long id = goals.create("Vacation", 2400.0, null, "USD").id();
        contributions.add(id, 500.0, "2026-06-26", null);
        contributions.add(id, 300.0, "2026-06-29", null);

        GoalProgress progress = serviceAsOf(LocalDate.of(2026, 7, 1)).progress().get(0);

        assertThat(progress.monthlyPace()).isEqualTo(800.0);
    }

    @Test
    @DisplayName("the funding plan's current pace uses the same one-month floor")
    void fundingPlanPaceUsesSameFloor() {
        long id = goals.create("Vacation", 2400.0, null, "USD").id();
        contributions.add(id, 800.0, FUNDING_TODAY.toString(), "Funding plan");

        GoalFundingSuggestion vacation = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0).goals().get(0);

        assertThat(vacation.monthlyPace()).isEqualTo(800.0);
    }

    @Test
    @DisplayName("sums contributions toward saved, including a withdrawal")
    void sumsContributions() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 600.0, "2026-06-01", null);
        contributions.add(id, -100.0, "2026-06-10", "had to dip in");

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.saved()).isEqualTo(500.0);
        assertThat(progress.remaining()).isEqualTo(500.0);
        assertThat(progress.percentComplete()).isEqualTo(50.0);
        assertThat(progress.contributionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a goal met or exceeded reports zero remaining and achieved, not a negative remaining")
    void goalExceeded() {
        long id = goals.create("Small target", 100.0, null, "USD").id();
        contributions.add(id, 150.0, "2026-06-01", null);

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.remaining()).isEqualTo(0.0);
        assertThat(progress.percentComplete()).isEqualTo(150.0);
        assertThat(progress.achieved()).isTrue();
    }

    @Test
    @DisplayName("lists every goal, newest first")
    void listsEveryGoal() {
        goals.create("First", 100.0, null, "USD");
        goals.create("Second", 200.0, null, "USD");

        List<GoalProgress> progress = service.progress();

        assertThat(progress).hasSize(2);
        assertThat(progress).extracting("name").containsExactly("Second", "First");
    }

    // --- funding plan -------------------------------------------------------------

    private static final LocalDate FUNDING_TODAY = LocalDate.of(2026, 7, 1);

    /** One month of income and spend on the default (USD) account. */
    private void seedMonth(String month, double income, double spend) {
        transactions.insertBatch(List.of(
                new ParsedTransaction(month + "-01", "PAYROLL", income, "credit", "Income"),
                new ParsedTransaction(month + "-15", "SUPERMARKET", spend, "debit", "Groceries")
        ), "funding-test-" + month, 1L);
    }

    private static GoalFundingSuggestion suggestionFor(GoalFundingPlan plan, String name) {
        return plan.goals().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    /** What a deadline goal needs per month, by the same 30.44-day-month rule the service uses. */
    private static double requiredPerMonth(double remaining, LocalDate targetDate) {
        double months = java.time.temporal.ChronoUnit.DAYS.between(FUNDING_TODAY, targetDate) / 30.44;
        return Math.round(remaining / Math.max(1.0, months) * 100.0) / 100.0;
    }

    @Test
    @DisplayName("a deadline goal is priced by the months left; leftover surplus is shared by undated goals")
    void deadlineFirstThenLeftoverToUndated() {
        seedMonth("2026-04", 3000, 2000);
        seedMonth("2026-05", 3000, 2000);
        seedMonth("2026-06", 3000, 2000);
        LocalDate deadline = LocalDate.of(2027, 1, 1);
        goals.create("Holiday", 1200.0, deadline.toString(), "USD");
        goals.create("Emergency fund", 5000.0, null, "USD");
        goals.create("New laptop", 2000.0, null, "USD");

        GoalFundingPlan plan = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0);

        double required = requiredPerMonth(1200.0, deadline);
        assertThat(plan.currency()).isEqualTo("USD");
        assertThat(plan.monthlySurplus()).isEqualTo(1000.0);
        assertThat(plan.monthsMeasured()).isEqualTo(3);
        assertThat(plan.totalRequired()).isEqualTo(required);
        assertThat(plan.covered()).isTrue();

        GoalFundingSuggestion holiday = suggestionFor(plan, "Holiday");
        assertThat(holiday.basis()).isEqualTo("deadline");
        assertThat(holiday.suggestedMonthly()).isEqualTo(required);

        // Whatever's left after the deadline is split evenly between the two undated goals.
        double share = (1000.0 - required) / 2;
        GoalFundingSuggestion fund = suggestionFor(plan, "Emergency fund");
        assertThat(fund.basis()).isEqualTo("surplus");
        assertThat(fund.suggestedMonthly()).isCloseTo(share, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fund.projectedCompletionDate()).isNotNull();
        assertThat(suggestionFor(plan, "New laptop").suggestedMonthly())
                .isEqualTo(fund.suggestedMonthly());
    }

    @Test
    @DisplayName("when deadlines need more than the surplus, the shortfall is reported and undated goals get nothing")
    void shortfallLeavesUndatedGoalsUnfunded() {
        seedMonth("2026-06", 2500, 2400);   // $100/month surplus
        goals.create("House deposit", 10000.0, "2027-07-01", "USD");
        goals.create("Someday", 500.0, null, "USD");

        GoalFundingPlan plan = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0);

        assertThat(plan.covered()).isFalse();
        assertThat(plan.leftover()).isNegative();
        // The deadline's figure is not shrunk to fit -- missing it quietly would be worse.
        assertThat(suggestionFor(plan, "House deposit").suggestedMonthly())
                .isEqualTo(requiredPerMonth(10000.0, LocalDate.of(2027, 7, 1)));
        GoalFundingSuggestion someday = suggestionFor(plan, "Someday");
        assertThat(someday.basis()).isEqualTo("unfunded");
        assertThat(someday.suggestedMonthly()).isNull();
        assertThat(someday.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("a deadline this month or already past needs the whole remainder now")
    void pastDeadlineNeedsEverythingNow() {
        long id = goals.create("Overdue", 1000.0, "2026-06-01", "USD").id();
        contributions.add(id, 400.0, "2026-05-01", null);

        GoalFundingSuggestion overdue = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0).goals().get(0);

        assertThat(overdue.remaining()).isEqualTo(600.0);
        assertThat(overdue.suggestedMonthly()).isEqualTo(600.0);
    }

    @Test
    @DisplayName("with no transactions there's no surplus to check against, but deadlines are still priced")
    void noTransactionsMeansNoSurplus() {
        goals.create("Holiday", 1200.0, "2027-01-01", "USD");

        GoalFundingPlan plan = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0);

        assertThat(plan.monthlySurplus()).isNull();
        assertThat(plan.monthsMeasured()).isZero();
        assertThat(plan.covered()).isNull();
        assertThat(plan.leftover()).isNull();
        assertThat(plan.totalRequired()).isPositive();
    }

    @Test
    @DisplayName("surplus averages the newest three months that have data, skipping gaps rather than counting them as zero")
    void surplusUsesNewestMonthsWithData() {
        seedMonth("2026-01", 10000, 0);     // older than the newest three -- ignored
        seedMonth("2026-03", 3000, 2000);
        // April has no transactions at all: a gap in what was imported, not a $0 month.
        seedMonth("2026-05", 3000, 2500);
        seedMonth("2026-06", 3000, 1500);
        goals.create("Anything", 1000.0, null, "USD");

        GoalFundingPlan plan = serviceAsOf(FUNDING_TODAY).fundingPlan().get(0);

        assertThat(plan.monthsMeasured()).isEqualTo(3);
        assertThat(plan.monthlySurplus()).isEqualTo(1000.0);   // (1000 + 500 + 1500) / 3
    }

    @Test
    @DisplayName("achieved goals are left out, and each currency gets its own plan")
    void achievedGoalsExcludedAndCurrenciesSeparate() {
        seedMonth("2026-06", 3000, 2000);
        long done = goals.create("Done", 100.0, null, "USD").id();
        contributions.add(done, 100.0, "2026-06-01", null);
        goals.create("Dollar goal", 1000.0, null, "USD");
        goals.create("Euro goal", 1000.0, null, "EUR");

        List<GoalFundingPlan> plans = serviceAsOf(FUNDING_TODAY).fundingPlan();

        assertThat(plans).extracting(GoalFundingPlan::currency).containsExactlyInAnyOrder("USD", "EUR");
        GoalFundingPlan usd = plans.stream().filter(p -> p.currency().equals("USD")).findFirst().orElseThrow();
        assertThat(usd.goals()).extracting(GoalFundingSuggestion::name).containsExactly("Dollar goal");
        // No EUR account exists, so the euro goal has no surplus to draw on -- the USD surplus
        // is never borrowed for it.
        GoalFundingPlan eur = plans.stream().filter(p -> p.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(eur.monthlySurplus()).isNull();
        assertThat(eur.goals().get(0).basis()).isEqualTo("unfunded");
    }

    @Test
    @DisplayName("no open goals means no plans at all")
    void noOpenGoalsNoPlans() {
        assertThat(serviceAsOf(FUNDING_TODAY).fundingPlan()).isEmpty();
    }
}
