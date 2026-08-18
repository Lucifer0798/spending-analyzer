package com.spendinganalyzer.model;

public record ParsedTransaction(
        String date,
        String description,
        double amount,
        String type,
        String category
) {}
