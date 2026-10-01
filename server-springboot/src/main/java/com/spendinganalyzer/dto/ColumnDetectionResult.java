package com.spendinganalyzer.dto;

import java.util.List;

/**
 * Returned (422) when the importer can't confidently map every required column (date,
 * description, and amount or debit/credit) on its own. {@code headers} is every column the file
 * actually has; the rest are whichever ones auto-detection *did* manage to guess, null for the
 * ones it couldn't -- so the frontend can prefill a mapping form with the good guesses and only
 * ask about the columns that actually need a human to pick them.
 */
public record ColumnDetectionResult(
        List<String> headers,
        String dateColumn,
        String descriptionColumn,
        String amountColumn,
        String debitColumn,
        String creditColumn,
        String categoryColumn
) {}
