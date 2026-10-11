package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A debit in one account and a credit in another, judged to be the same money moving between
 * them. {@code status} is "confirmed" (both sides were moved into a transfer category) or
 * "dismissed" ("not a transfer" -- kept only so the suggestion isn't made again).
 *
 * <p>The {@code *Prev*} fields are what each side's category was before confirming, so undoing
 * puts it back; they're null on a dismissal, which never changed anything.
 */
public record TransferPair(
        long id,
        @JsonProperty("debit_id") long debitId,
        @JsonProperty("credit_id") long creditId,
        String status,
        @JsonProperty("debit_prev_category") String debitPrevCategory,
        @JsonProperty("debit_prev_source") String debitPrevSource,
        @JsonProperty("credit_prev_category") String creditPrevCategory,
        @JsonProperty("credit_prev_source") String creditPrevSource,
        @JsonProperty("created_at") String createdAt
) {
    public static final String CONFIRMED = "confirmed";
    public static final String DISMISSED = "dismissed";
}
