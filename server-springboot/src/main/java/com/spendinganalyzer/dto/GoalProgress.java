package com.spendinganalyzer.dto;

/**
 * A goal measured against what has actually been logged toward it.
 *
 * @param remaining        never negative -- a goal met or exceeded has nothing left to go, rather
 *                          than reading as a confusing "negative amount remaining"
 * @param percentComplete   uncapped, so a goal exceeded still reads as more than 100
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
        int contributionCount
) {}
