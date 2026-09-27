package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A receipt image or PDF attached to one transaction -- at most one per transaction. */
public record TransactionReceipt(
        @JsonProperty("transaction_id") long transactionId,
        String filename,
        @JsonProperty("content_type") String contentType,
        byte[] data,
        @JsonProperty("uploaded_at") String uploadedAt
) {}
