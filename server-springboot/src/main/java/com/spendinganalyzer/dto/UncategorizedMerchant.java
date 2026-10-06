package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Every uncategorized transaction at one merchant, in one currency -- the unit the review queue
 * asks about, so one answer categorizes them all and teaches merchant memory once.
 *
 * @param merchant     the normalized merchant key -- what merchant memory will learn
 * @param examples     up to three distinct raw descriptions, so the merchant is recognisable
 *                     when the cleaned-up key alone isn't
 * @param total        the sum of the amounts, unsigned; {@code debits}/{@code credits} say
 *                     which direction they went
 * @param transactionIds what to send to {@code PATCH /transactions/bulk-category}
 */
public record UncategorizedMerchant(
        String merchant,
        String currency,
        int count,
        double total,
        int debits,
        int credits,
        @JsonProperty("first_date") String firstDate,
        @JsonProperty("last_date") String lastDate,
        List<String> examples,
        @JsonProperty("transaction_ids") List<Long> transactionIds
) {}
