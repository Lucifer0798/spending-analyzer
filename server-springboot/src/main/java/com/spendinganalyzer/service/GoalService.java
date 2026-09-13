package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalContributionRepository.GoalTotals;
import com.spendinganalyzer.repository.GoalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Measures each goal against what has actually been logged toward it. */
@Service
public class GoalService {

    /** For converting a $/day pace into a $/month one without assuming a fixed-length month. */
    private static final double AVG_DAYS_PER_MONTH = 30.44;

    private final GoalRepository goals;
    private final GoalContributionRepository contributions;
    private final Clock clock;

    @Autowired
    public GoalService(GoalRepository goals, GoalContributionRepository contributions) {
        this(goals, contributions, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today" instead of asserting against a moving target. */
    GoalService(GoalRepository goals, GoalContributionRepository contributions, Clock clock) {
        this.goals = goals;
        this.contributions = contributions;
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

            double monthlyPace = 0;
            String projectedDate = null;
            if (t.firstContributionDate() != null) {
                // Measured from the very first contribution, not a recent window: a handful of
                // logged amounts is too little history to average over a trailing period the way
                // spend forecasting does with months of transactions.
                long daysElapsed = Math.max(
                        1, ChronoUnit.DAYS.between(LocalDate.parse(t.firstContributionDate()), today));
                monthlyPace = round2((t.sum() / daysElapsed) * AVG_DAYS_PER_MONTH);

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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
