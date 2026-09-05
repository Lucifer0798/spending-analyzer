package com.spendinganalyzer.dto;

/**
 * One category's spend in the current period versus the immediately preceding period of the
 * same length. {@code changePercent} is null when {@code previousTotal} is zero — there is no
 * meaningful percentage change from nothing, and reporting one (or an infinite one) would be a
 * number that looks precise but means nothing.
 */
public record CategoryComparison(
        String category,
        double currentTotal,
        double previousTotal,
        double changeAmount,
        Double changePercent
) {}
