package com.spendinganalyzer.model;

/**
 * A short spending challenge -- see V30. {@code limitAmount} 0 is a no-spend challenge;
 * {@code category} null means all spending; {@code accountId} null means every account.
 */
public record Challenge(
        long id,
        String name,
        String category,
        double limitAmount,
        String startDate,
        String endDate,
        Long accountId,
        String createdAt
) {
    public boolean noSpend() {
        return limitAmount == 0;
    }
}
