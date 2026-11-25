package com.spendinganalyzer.model;

/**
 * Money paid back toward a split charge ({@code transactionId}): how much, when, and -- if it
 * arrived as a transaction of its own, such as a Zelle credit -- which one ({@code repaymentId},
 * null otherwise).
 */
public record SplitRepayment(long id, long transactionId, double amount, String repaidOn, Long repaymentId,
                             String createdAt) {}
