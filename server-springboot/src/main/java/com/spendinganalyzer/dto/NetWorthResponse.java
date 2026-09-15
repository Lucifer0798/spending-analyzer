package com.spendinganalyzer.dto;

import java.util.List;

/**
 * Mirrors {@link SummaryResponse}'s duality: a single combined total when every active account
 * agrees on a currency, or {@code null} here with {@code perCurrency} populated instead once they
 * don't -- summing balances across currencies would be as meaningless as summing spend across them.
 */
public record NetWorthResponse(
        double total,
        List<NetWorthAccount> accounts,
        List<NetWorthPoint> history,
        String currency,
        List<CurrencyNetWorth> perCurrency
) {}
