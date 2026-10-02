package com.spendinganalyzer.dto;

import java.util.List;

/**
 * How much to put toward each open goal every month, for the goals sharing one currency -- set
 * against what that currency's accounts actually have left over each month.
 *
 * @param monthlySurplus  average monthly income minus spend over the most recent
 *                        {@code monthsMeasured} months that have any transactions in this
 *                        currency; null when there are none, in which case {@code covered} and
 *                        {@code leftover} are null too -- deadlines can still be priced, but
 *                        there's nothing to check them against
 * @param totalRequired   what every dated goal together needs per month to finish on time
 * @param covered         whether the surplus pays for every dated goal's requirement
 * @param leftover        surplus minus {@code totalRequired}; negative is a monthly shortfall
 */
public record GoalFundingPlan(
        String currency,
        Double monthlySurplus,
        int monthsMeasured,
        double totalRequired,
        Boolean covered,
        Double leftover,
        List<GoalFundingSuggestion> goals
) {}
