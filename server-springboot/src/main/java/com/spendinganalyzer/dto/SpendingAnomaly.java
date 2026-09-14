package com.spendinganalyzer.dto;

/**
 * A transaction whose amount stood out against its own category's typical spend.
 *
 * @param typicalAmount the category's median amount — robust to the anomaly itself skewing an
 *                       average the way a single huge outlier would
 * @param multiplier     how many times the typical amount this transaction came to
 */
public record SpendingAnomaly(
        long transactionId,
        String date,
        String description,
        String category,
        double amount,
        double typicalAmount,
        double multiplier
) {}
