package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param splitShare what you're responsible for, out of {@code amount} -- null means the
 *                    transaction isn't split, so the full amount is your share.
 * @param splitNote   who the rest belongs to; null whenever {@code splitShare} is.
 */
public record Transaction(
        long id,
        String date,
        String description,
        double amount,
        String type,
        String category,
        @JsonProperty("category_source") String categorySource,
        @JsonProperty("upload_batch_id") String uploadBatchId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("account_id") long accountId,
        @JsonProperty("account_name") String accountName,
        @JsonProperty("account_currency") String accountCurrency,
        @JsonProperty("split_share") Double splitShare,
        @JsonProperty("split_note") String splitNote
) {}
