package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A saved combination of the Transactions page's filters, so a view like "Business trips" can be
 * reapplied in one click. Every field but the name is optional — a preset needs only say what it
 * actually filters on, leaving the rest unbounded.
 */
public record FilterPreset(
        long id,
        String name,
        String category,
        String tag,
        String search,
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("date_from") String dateFrom,
        @JsonProperty("date_to") String dateTo,
        @JsonProperty("created_at") String createdAt
) {}
