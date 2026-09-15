package com.spendinganalyzer.dto;

import java.util.List;

/** One currency's slice of a {@link NetWorthResponse} whose accounts don't all share one. */
public record CurrencyNetWorth(
        String currency,
        double total,
        List<NetWorthAccount> accounts,
        List<NetWorthPoint> history
) {}
