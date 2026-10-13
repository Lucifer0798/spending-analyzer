package com.spendinganalyzer.model;

/**
 * One row of an uploaded statement, before it's stored.
 *
 * @param externalId the bank's own ID for the transaction -- an OFX FITID. Null for CSV and Excel,
 *                   which have no such thing.
 */
public record ParsedTransaction(
        String date,
        String description,
        double amount,
        String type,
        String category,
        String externalId
) {
    /** A row with no bank ID -- every CSV and Excel row. */
    public ParsedTransaction(String date, String description, double amount, String type, String category) {
        this(date, description, amount, type, category, null);
    }

    /**
     * NUL can't occur in any field, so fields can't run together into a false match. Built from
     * its code point: written literally it was an invisible byte that made tools treat this file
     * as binary.
     */
    private static final String KEY_SEPARATOR = String.valueOf((char) 0);

    /**
     * Identity used for duplicate detection. Two rows with the same key within the same
     * account describe the same real-world transaction. Deliberately excludes category,
     * which the importer may or may not supply for otherwise identical rows.
     */
    public String dedupeKey() {
        return String.join(KEY_SEPARATOR,
                date,
                description.trim().toLowerCase(),
                String.format("%.2f", amount),
                type);
    }
}
