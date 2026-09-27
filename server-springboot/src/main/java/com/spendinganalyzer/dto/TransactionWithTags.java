package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.spendinganalyzer.model.Transaction;

import java.util.List;

/**
 * A transaction with its tags flattened alongside its own fields in JSON, plus whether it has a
 * receipt attached -- a lightweight flag, not the receipt itself, which is fetched separately
 * (GET /transactions/{id}/receipt) so the list response never has to carry receipt bytes.
 */
public record TransactionWithTags(
        @JsonUnwrapped Transaction transaction,
        List<String> tags,
        @JsonProperty("has_receipt") boolean hasReceipt
) {}
