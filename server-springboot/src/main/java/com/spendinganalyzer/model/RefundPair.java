package com.spendinganalyzer.model;

/**
 * A purchase and a refund of (some or all of) it. {@code status} is "confirmed" -- the refund comes
 * off the purchase in spending totals and stops counting as income -- or "dismissed" ("not a refund
 * for this", kept only so the suggestion isn't made again). Neither transaction is changed either
 * way, so there's nothing to remember for undoing it.
 */
public record RefundPair(long id, long purchaseId, long refundId, String status, String createdAt) {
    public static final String CONFIRMED = "confirmed";
    public static final String DISMISSED = "dismissed";
}
