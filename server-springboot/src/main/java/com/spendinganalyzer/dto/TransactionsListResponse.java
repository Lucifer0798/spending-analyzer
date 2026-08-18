package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Transaction;

import java.util.List;

public record TransactionsListResponse(List<Transaction> transactions, int total) {}
