package com.spendinganalyzer.dto;

import java.util.List;

/**
 * {@code currency} is the single currency every amount here is denominated in, or null when the
 * accounts in scope ("all accounts", with more than one currency among them) don't share one —
 * in that case the three lists above are empty and {@code perCurrency} carries the same shape
 * split out per currency instead, since summing across currencies would be meaningless.
 */
public record SummaryResponse(
        List<CategoryTotal> categoryTotals,
        List<MonthlyTotal> monthlyTotals,
        List<CategoryMonthlySeries> monthlyByCategory,
        String currency,
        List<CurrencyBreakdown> perCurrency
) {}
