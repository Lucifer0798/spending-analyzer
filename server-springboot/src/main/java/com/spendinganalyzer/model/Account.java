package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Account(
        long id,
        String name,
        String type,
        boolean archived,
        @JsonProperty("created_at") String createdAt
) {
    public static final List<String> TYPES =
            List.of("checking", "savings", "credit_card", "cash", "investment", "other");

    /** Account rows imported before accounts existed, and the fallback for uploads with no account. */
    public static final long DEFAULT_ID = 1L;
}
