package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalContributionRepository.GoalTotals;
import com.spendinganalyzer.repository.GoalRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Measures each goal against what has actually been logged toward it. */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final GoalContributionRepository contributions;

    public GoalService(GoalRepository goals, GoalContributionRepository contributions) {
        this.goals = goals;
        this.contributions = contributions;
    }

    public List<GoalProgress> progress() {
        Map<Long, GoalTotals> totals = contributions.totalsByGoal();
        List<GoalProgress> rows = new ArrayList<>();

        for (Goal goal : goals.findAll()) {
            GoalTotals t = totals.getOrDefault(goal.id(), GoalTotals.NONE);
            double percent = (t.sum() / goal.targetAmount()) * 100;

            rows.add(new GoalProgress(
                    goal.id(),
                    goal.name(),
                    goal.targetAmount(),
                    goal.targetDate(),
                    goal.currency(),
                    round2(t.sum()),
                    round2(Math.max(0, goal.targetAmount() - t.sum())),
                    round2(percent),
                    t.sum() >= goal.targetAmount(),
                    t.count()
            ));
        }
        return rows;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
