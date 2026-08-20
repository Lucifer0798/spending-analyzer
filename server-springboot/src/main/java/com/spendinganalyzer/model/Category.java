package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Category(
        long id,
        String name,
        @JsonProperty("is_builtin") boolean isBuiltin,
        @JsonProperty("is_income") boolean isIncome,
        @JsonProperty("is_transfer") boolean isTransfer,
        @JsonProperty("sort_order") int sortOrder
) {}
