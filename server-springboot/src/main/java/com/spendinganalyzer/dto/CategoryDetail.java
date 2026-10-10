package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Transaction;

import java.util.List;

/**
 * Everything about one category's spend in a range, for its drill-down page.
 *
 * @param applicable      false when "all accounts" spans more than one currency
 * @param sharePercent    this category's slice of all spend in range
 * @param averagePerMonth total over the months in {@code months} -- months with spend anywhere in
 *                        range, so a month this category skipped counts as zero, not as missing
 * @param months          every month with any spend in range, zero where this category had none
 * @param change          against the period before, when the range has both ends (null otherwise
 *                        -- the same rule as the dashboard's comparison card)
 * @param recent          the newest transactions, up to ten
 */
public record CategoryDetail(
        boolean applicable,
        String category,
        String currency,
        double total,
        int count,
        double sharePercent,
        double averagePerMonth,
        List<MonthlyTotal> months,
        CategoryComparison change,
        List<MerchantTotal> topMerchants,
        int merchantCount,
        List<Transaction> recent
) {
    public static CategoryDetail notApplicable(String category) {
        return new CategoryDetail(false, category, null, 0, 0, 0, 0, List.of(), null, List.of(), 0, List.of());
    }
}
