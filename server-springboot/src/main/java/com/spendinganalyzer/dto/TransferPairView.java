package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spendinganalyzer.model.Transaction;

/** A recorded pair with both of its transactions attached, for listing. */
public record TransferPairView(
        long id,
        String status,
        @JsonProperty("created_at") String createdAt,
        Transaction debit,
        Transaction credit
) {}
