package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.spendinganalyzer.model.Transaction;

import java.util.List;

/** A transaction with its tags flattened alongside its own fields in JSON. */
public record TransactionWithTags(@JsonUnwrapped Transaction transaction, List<String> tags) {}
