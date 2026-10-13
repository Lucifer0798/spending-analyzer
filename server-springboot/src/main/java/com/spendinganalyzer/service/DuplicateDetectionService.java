package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which rows of an upload are already present.
 *
 * <p>Matching is count-aware rather than exact-match. Two identical coffees on the same
 * day are a legitimate pair, not a duplicate, so a key that appears twice in the file and
 * once in the database contributes one new row. Only the surplus is imported, which makes
 * re-uploading an overlapping statement idempotent without discarding real repeats.
 */
@Service
public class DuplicateDetectionService {

    public record Result(List<ParsedTransaction> toInsert, int skipped) {}

    /**
     * @param incoming       rows parsed from the uploaded file, in file order
     * @param existingCounts how many times each dedupe key already exists in the target account
     */
    public Result filterDuplicates(List<ParsedTransaction> incoming, Map<String, Integer> existingCounts) {
        return filterDuplicates(incoming, existingCounts, Set.of());
    }

    /**
     * As above, for rows that may carry the bank's own ID (OFX). A row whose ID the account already
     * holds -- or that appeared earlier in the same file -- is a duplicate outright, whatever its
     * description says now. Any other row still goes through the count-based check, so an OFX
     * download overlapping a CSV imported earlier (no IDs stored) isn't imported twice; the caller
     * passes counts of ID-less rows only for that, see {@code countExistingKeys}.
     */
    public Result filterDuplicates(List<ParsedTransaction> incoming, Map<String, Integer> existingCounts,
                                   Set<String> existingExternalIds) {
        // Copy so the caller's map is not mutated as allowances are consumed.
        Map<String, Integer> remaining = new HashMap<>(existingCounts);
        Set<String> seenIds = new HashSet<>(existingExternalIds);
        List<ParsedTransaction> toInsert = new ArrayList<>();
        int skipped = 0;

        for (ParsedTransaction t : incoming) {
            if (t.externalId() != null && !seenIds.add(t.externalId())) {
                skipped++;
                continue;
            }
            String key = t.dedupeKey();
            int alreadyPresent = remaining.getOrDefault(key, 0);
            if (alreadyPresent > 0) {
                remaining.put(key, alreadyPresent - 1);
                skipped++;
            } else {
                toInsert.add(t);
            }
        }

        return new Result(toInsert, skipped);
    }

    /** Earliest date in the batch, used to bound the existing-rows lookup. */
    public String minDate(List<ParsedTransaction> transactions) {
        return transactions.stream().map(ParsedTransaction::date).min(String::compareTo).orElse("0000-01-01");
    }

    /** Latest date in the batch, used to bound the existing-rows lookup. */
    public String maxDate(List<ParsedTransaction> transactions) {
        return transactions.stream().map(ParsedTransaction::date).max(String::compareTo).orElse("9999-12-31");
    }
}
