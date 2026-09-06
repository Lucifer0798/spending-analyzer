package com.spendinganalyzer.dto;

import java.util.List;

/** One currency's slice of {@link SummaryResponse}, used when "all accounts" spans more than one. */
public record CurrencyBreakdown(
        String currency,
        List<CategoryTotal> categoryTotals,
        List<MonthlyTotal> monthlyTotals
) {}
