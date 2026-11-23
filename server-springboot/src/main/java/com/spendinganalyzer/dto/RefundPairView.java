package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Transaction;

/** A recorded refund pair with both of its transactions attached, for listing. */
public record RefundPairView(long id, String status, String createdAt, Transaction purchase, Transaction refund) {}
