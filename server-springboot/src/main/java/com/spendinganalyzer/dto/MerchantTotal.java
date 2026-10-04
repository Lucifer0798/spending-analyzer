package com.spendinganalyzer.dto;

/**
 * Spend at one merchant, keyed by {@code MerchantNormalizer} so every branch of a chain and every
 * order reference counts as the same place -- the same key merchant memory and recurring detection use.
 *
 * @param averageAmount  total divided by count: the typical size of one visit
 * @param topCategory    the category most of this merchant's spend landed in, by amount;
 *                       "Uncategorized" when none was set
 */
public record MerchantTotal(
        String merchant,
        double total,
        int count,
        double averageAmount,
        String firstDate,
        String lastDate,
        String topCategory
) {}
