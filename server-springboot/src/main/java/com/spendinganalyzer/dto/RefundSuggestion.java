package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Transaction;

/**
 * A credit that looks like a refund of an earlier purchase: same account, same merchant, no bigger
 * than what's left of the purchase, at most {@code RefundMatchingService.MAX_DAYS_AFTER} days later.
 *
 * @param refunded    what earlier confirmed refunds already took off the purchase
 * @param exactAmount whether the refund is the purchase's whole amount (what's left of it)
 */
public record RefundSuggestion(Transaction purchase, Transaction refund, int daysAfter, double refunded,
                               boolean exactAmount) {}
