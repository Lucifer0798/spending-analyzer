package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One manually-logged balance for an account, as of a date. Positive is an asset, negative is a
 * liability (an outstanding credit card bill, say) -- the number is taken literally, with no
 * sign-flip based on account type.
 */
public record AccountBalance(
        long id,
        @JsonProperty("account_id") long accountId,
        String date,
        double balance,
        @JsonProperty("created_at") String createdAt
) {}
