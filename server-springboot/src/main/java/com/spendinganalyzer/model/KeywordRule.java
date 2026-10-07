package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** "A description containing {@code keyword} as a whole word goes in {@code category}." */
public record KeywordRule(
        long id,
        String keyword,
        String category,
        @JsonProperty("created_at") String createdAt
) {}
