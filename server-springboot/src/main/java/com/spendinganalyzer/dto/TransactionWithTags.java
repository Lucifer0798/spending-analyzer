package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.spendinganalyzer.model.Transaction;

import java.util.List;

/**
 * A transaction with its tags flattened alongside its own fields in JSON, plus whether it has a
 * receipt attached -- a lightweight flag, not the receipt itself, which is fetched separately
 * (GET /transactions/{id}/receipt) so the list response never has to carry receipt bytes.
 *
 * @param refunded  on a purchase, what confirmed refunds took off it; null when none
 * @param refundFor on a confirmed refund, the description of the purchase it refunds; null otherwise
 * @param owedLeft  on a split charge, what's still owed to you (zero once settled); null otherwise
 * @param repaymentFor on a credit recorded as money paid back, the description of the split charge
 *                  it repays; null otherwise
 */
public record TransactionWithTags(
        @JsonUnwrapped Transaction transaction,
        List<String> tags,
        @JsonProperty("has_receipt") boolean hasReceipt,
        Double refunded,
        @JsonProperty("refund_for") String refundFor,
        @JsonProperty("owed_left") Double owedLeft,
        @JsonProperty("repayment_for") String repaymentFor
) {}
