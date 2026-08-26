package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A monthly spending target for one category. */
public record Budget(
        long id,
        String category,
        @JsonProperty("monthly_limit") double monthlyLimit,
        @JsonProperty("updated_at") String updatedAt
) {}
