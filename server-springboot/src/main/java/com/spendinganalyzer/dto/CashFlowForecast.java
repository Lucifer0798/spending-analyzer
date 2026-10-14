package com.spendinganalyzer.dto;

import java.util.List;

/**
 * Each account's balance projected forward from its latest logged balance -- see {@link
 * com.spendinganalyzer.service.CashFlowService}.
 *
 * @param today            the date the horizon is measured from
 * @param accounts         accounts with at least one logged balance, in display order
 * @param withoutBalance   active accounts with no logged balance, so nothing to start from
 */
public record CashFlowForecast(
        String today,
        int horizonDays,
        List<AccountForecast> accounts,
        List<String> withoutBalance
) {
    /**
     * @param balanceDate       the latest logged balance's date, and {@code loggedBalance} its amount
     * @param asOf              the date the starting balance is known through: the logged balance
     *                          plus every transaction imported since, up to the newest one
     * @param importedSince     how many of those transactions there were
     * @param stale             the newest data is old enough that the forecast is mostly guesswork
     * @param firstNegativeDate the first projected day below zero, or null
     * @param warn              {@code firstNegativeDate} is set on an account where that's a problem
     *                          (checking, savings, cash) -- a credit card below zero is just owed
     */
    public record AccountForecast(
            long accountId,
            String accountName,
            String accountType,
            String currency,
            String balanceDate,
            double loggedBalance,
            String asOf,
            int importedSince,
            double startingBalance,
            boolean stale,
            List<Event> events,
            List<Point> daily,
            double endingBalance,
            double lowestBalance,
            String lowestDate,
            String firstNegativeDate,
            boolean warn
    ) {}

    /**
     * One projected transaction. {@code amount} is signed: negative leaves the account.
     *
     * @param overdue it was due on or before {@code asOf} and hasn't appeared -- counted once, on the
     *                first projected day, in case it's merely late
     * @param cancelling flagged to cancel on the Recurring page; still counted until it stops
     */
    public record Event(
            String date,
            String description,
            double amount,
            String cadence,
            double balanceAfter,
            boolean overdue,
            boolean cancelling
    ) {}

    public record Point(String date, double balance) {}
}
