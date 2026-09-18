package com.spendinganalyzer.dto;

import java.util.List;

/**
 * The active date range versus another period — by default the one immediately before it, of
 * the same length ("this month vs last month" when the filter is a month, "this quarter vs last
 * quarter" when it's three), without the app needing to know which preset produced the range.
 * {@code previousRange} can instead be an arbitrary, independently-chosen range the caller picked
 * outright, in which case {@code custom} is true and the two ranges need not share a length.
 *
 * <p>Categories are sorted by {@code changeAmount} descending, so the biggest increase leads and
 * the biggest decrease trails — the two ends of the list are what a "what changed" view is for.
 */
public record PeriodComparison(
        DateRange currentRange,
        DateRange previousRange,
        double currentTotal,
        double previousTotal,
        double changeAmount,
        Double changePercent,
        List<CategoryComparison> categories,
        boolean custom
) {}
