package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MerchantCategory(
        long id,
        @JsonProperty("merchant_key") String merchantKey,
        String category,
        /** 'user' entries are corrections and outrank 'ai' guesses. */
        String source,
        @JsonProperty("hit_count") int hitCount,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_USER = "user";
}
