package com.spendinganalyzer.dto;

import java.util.List;

/**
 * Where the money in a range came from and went: income per income category on one side, spend
 * per category (with its category group) on the other, and the difference as either {@code saved}
 * or {@code shortfall} -- at most one of the two is non-zero.
 *
 * @param applicable false across accounts in more than one currency, the same refusal every
 *                   other total makes
 */
public record MoneyFlow(
        boolean applicable,
        String currency,
        List<Source> income,
        List<Sink> spending,
        double totalIncome,
        double totalSpending,
        double saved,
        double shortfall
) {
    public record Source(String category, double total) {}

    /** {@code group} is null for an ungrouped category, which then stands on its own. */
    public record Sink(String category, String group, double total) {}

    public static final MoneyFlow NOT_APPLICABLE = new MoneyFlow(false, null, List.of(), List.of(), 0, 0, 0, 0);
}
