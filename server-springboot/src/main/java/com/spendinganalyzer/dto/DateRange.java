package com.spendinganalyzer.dto;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * An inclusive date window for filtering. Either bound may be null, meaning open-ended,
 * so "everything before March" and "everything since March" are both expressible.
 */
public record DateRange(String from, String to) {

    public static final DateRange ALL = new DateRange(null, null);

    /**
     * Builds a range from request parameters, rejecting anything that is not an ISO date
     * so a malformed value fails loudly instead of silently matching nothing.
     */
    public static DateRange of(String from, String to) {
        String validFrom = validate(from, "from");
        String validTo = validate(to, "to");
        if (validFrom != null && validTo != null && validFrom.compareTo(validTo) > 0) {
            throw new IllegalArgumentException("'from' (" + validFrom + ") is after 'to' (" + validTo + ").");
        }
        return new DateRange(validFrom, validTo);
    }

    private static String validate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be a date in YYYY-MM-DD form, got: " + value);
        }
    }

    public boolean isUnbounded() {
        return from == null && to == null;
    }
}
