package com.spendinganalyzer.dto;

import java.util.List;

public record TransactionsListResponse(List<TransactionWithTags> transactions, int total) {}
