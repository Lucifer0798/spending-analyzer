package com.spendinganalyzer.dto;

/**
 * One category's budget measured against what was actually spent in a month.
 *
 * @param remaining   negative once the budget is blown, which is more useful than clamping to zero
 * @param percentUsed uncapped, so 140% reads as 140 rather than a saturated 100
 * @param status      one of {@code under}, {@code near}, {@code over}
 */
public record BudgetProgress(
        long id,
        String category,
        double monthlyLimit,
        double spent,
        double remaining,
        double percentUsed,
        String status
) {}
