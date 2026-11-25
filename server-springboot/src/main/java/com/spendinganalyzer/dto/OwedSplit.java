package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Transaction;

import java.util.List;

/**
 * A split charge with money still owed on it.
 *
 * @param owed        what isn't yours: the amount less your share
 * @param repaid      paid back so far
 * @param outstanding still to come back -- owed less repaid, never below zero
 * @param suggestions credits that look like the repayment: from the person the note names, or for
 *                    exactly what's outstanding -- at most a few, the likeliest first
 */
public record OwedSplit(Transaction transaction, double owed, double repaid, double outstanding,
                        List<Repayment> repayments, List<Transaction> suggestions) {

    /** One repayment, with the credit it arrived as when there is one. */
    public record Repayment(long id, double amount, String repaidOn, Transaction credit) {}
}
