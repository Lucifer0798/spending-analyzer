package com.spendinganalyzer.dto;

/**
 * One open goal's suggested monthly contribution.
 *
 * @param basis                   {@code "deadline"} -- what it takes to finish by the goal's
 *                                target date; {@code "surplus"} -- an even share of what's left
 *                                after every deadline is paid for, for a goal with no date; or
 *                                {@code "unfunded"} -- a goal with no date and nothing left over
 *                                to give it, so {@code suggestedMonthly} is null
 * @param monthlyPace             the goal's actual contribution rate so far, for comparison --
 *                                the same figure {@link GoalProgress#monthlyPace()} reports
 * @param projectedCompletionDate only for {@code "surplus"}: when the goal would be reached at the
 *                                suggested rate. A deadline goal finishes on its target date by
 *                                construction, and an unfunded one has no rate to project from
 */
public record GoalFundingSuggestion(
        long goalId,
        String name,
        double remaining,
        String targetDate,
        Double suggestedMonthly,
        String basis,
        double monthlyPace,
        String projectedCompletionDate
) {}
