package com.spendinganalyzer.dto;

/**
 * One category's budget measured against what was actually spent in a month.
 *
 * @param monthlyLimit the limit actually in effect for the measured month -- the base target,
 *                      escalated forward if a schedule is set. This is what {@code spent} is
 *                      compared against.
 * @param baseLimit     the raw target as originally set, before any escalation is applied
 * @param remaining     negative once the budget is blown, which is more useful than clamping to zero
 * @param percentUsed   uncapped, so 140% reads as 140 rather than a saturated 100
 * @param status        one of {@code under}, {@code near}, {@code over}
 */
public record BudgetProgress(
        long id,
        String category,
        double monthlyLimit,
        double baseLimit,
        double spent,
        double remaining,
        double percentUsed,
        String status,
        String escalationType,
        Double escalationValue,
        Integer escalationFrequencyMonths,
        String escalationStartMonth
) {}
