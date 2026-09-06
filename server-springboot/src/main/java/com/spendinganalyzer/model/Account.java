package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Account(
        long id,
        String name,
        String type,
        boolean archived,
        @JsonProperty("created_at") String createdAt,
        String currency
) {
    public static final List<String> TYPES =
            List.of("checking", "savings", "credit_card", "cash", "investment", "other");

    /**
     * Common ISO 4217 codes offered in the account form. Not exhaustive — {@link java.util.Currency}
     * recognizes every valid code, and that is what actually validates a create/update request, so
     * a currency missing from this list can still be set, just not picked from the dropdown.
     */
    public static final List<String> CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY", "INR", "MXN",
            "BRL", "SGD", "NZD", "HKD", "SEK", "NOK", "DKK", "ZAR", "AED", "KRW");

    /** Account rows imported before accounts existed, and the fallback for uploads with no account. */
    public static final long DEFAULT_ID = 1L;
}
