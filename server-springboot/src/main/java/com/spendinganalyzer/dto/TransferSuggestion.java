package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spendinganalyzer.model.Transaction;

/**
 * A debit and a credit that look like the same money moving between two of your accounts: same
 * currency, same amount, different accounts, a few days apart at most.
 */
public record TransferSuggestion(
        Transaction debit,
        Transaction credit,
        @JsonProperty("days_apart") int daysApart
) {}
