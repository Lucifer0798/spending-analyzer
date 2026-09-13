package com.spendinganalyzer.dto;

/**
 * A goal measured against what has actually been logged toward it.
 *
 * @param remaining               never negative -- a goal met or exceeded has nothing left to go,
 *                                 rather than reading as a confusing "negative amount remaining"
 * @param percentComplete         uncapped, so a goal exceeded still reads as more than 100
 * @param monthlyPace             the average net contribution rate since the first one logged,
 *                                projected to a 30.44-day month; zero when nothing's been logged
 *                                yet, and can be negative when withdrawals have outpaced deposits
 * @param projectedCompletionDate null when there's no pace to project from (no contributions, the
 *                                goal is already achieved, or the pace isn't positive) -- otherwise
 *                                the date this goal would be reached at the current pace
 */
public record GoalProgress(
        long id,
        String name,
        double targetAmount,
        String targetDate,
        String currency,
        double saved,
        double remaining,
        double percentComplete,
        boolean achieved,
        int contributionCount,
        double monthlyPace,
        String projectedCompletionDate
) {}
