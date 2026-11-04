package com.spendinganalyzer.dto;

import java.util.List;

/**
 * Spend at one merchant, keyed by {@code MerchantNormalizer} so every branch of a chain and every
 * order reference counts as the same place -- the same key merchant memory and recurring detection use.
 *
 * @param merchant       the merchant's name when it has one (merged spellings share it), else its key
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
        String topCategory,
        /** The merchant keys behind this row -- one, or several merged under one name -- for renaming it. */
        List<String> keys,
        /** Whether it has a name, rather than showing its key. */
        boolean named
) {}
