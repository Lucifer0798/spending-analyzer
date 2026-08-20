package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // Copy so the caller's map is not mutated as allowances are consumed.
        Map<String, Integer> remaining = new HashMap<>(existingCounts);
        List<ParsedTransaction> toInsert = new ArrayList<>();
        int skipped = 0;

        for (ParsedTransaction t : incoming) {
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
