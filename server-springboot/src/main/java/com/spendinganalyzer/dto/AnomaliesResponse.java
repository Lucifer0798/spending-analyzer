package com.spendinganalyzer.dto;

import java.util.List;

public record AnomaliesResponse(
        List<SpendingAnomaly> anomalies,
        String currency,
        /** True when "all accounts" spans more than one currency -- {@code anomalies} is empty
         *  in that case, the same rule {@code /recurring} follows. */
        boolean mixedCurrencies
) {}
