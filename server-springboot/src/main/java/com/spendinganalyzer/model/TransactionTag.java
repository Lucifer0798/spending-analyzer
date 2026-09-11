package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One tag applied to one transaction. No id of its own — (transaction_id, tag_id) is the key. */
public record TransactionTag(
        @JsonProperty("transaction_id") long transactionId,
        @JsonProperty("tag_id") long tagId,
        @JsonProperty("created_at") String createdAt
) {}
