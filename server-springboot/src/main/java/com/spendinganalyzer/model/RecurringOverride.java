package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RecurringOverride(
        long id,
        @JsonProperty("merchant_key") String merchantKey,
        String action,
        @JsonProperty("created_at") String createdAt
) {
    /** A reminder that you're in the process of cancelling this, until the charges stop. */
    public static final String ACTION_CANCEL = "cancel";

    /** Hides this merchant from recurring detection for good — never really a subscription. */
    public static final String ACTION_EXCLUDE = "exclude";
}
