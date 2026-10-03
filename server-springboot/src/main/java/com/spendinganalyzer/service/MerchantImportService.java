package com.spendinganalyzer.service;

import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads merchant memory back in from the CSV {@link CsvExportService#merchantRules} writes, so
 * learned categorisation can move between instances or be bulk-edited in a spreadsheet.
 *
 * <p>All or nothing: every row is validated before anything is written, and a file with any bad
 * row is rejected whole with the line numbers that need fixing. Half an import would leave memory
 * in a state that matches neither the file nor what was there before.
 */
@Service
public class MerchantImportService {

    /** Enough to show the pattern in a broken file without burying the message. */
    static final int MAX_REPORTED_ERRORS = 10;

    /** U+FEFF, built from its code point so the source holds no invisible character. */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    private final MerchantCategoryRepository merchants;
    private final CategoryRepository categories;

    public MerchantImportService(MerchantCategoryRepository merchants, CategoryRepository categories) {
        this.merchants = merchants;
        this.categories = categories;
    }

    /** One validated row, ready to write. */
    public record ImportedRule(String merchantKey, String category, double minAmount, double maxAmount, String source) {}

    /**
     * @param created rules for a merchant and band that weren't in memory before
     * @param updated rules that replaced an existing one for the same merchant and band (always 0
     *                when replacing, since memory was emptied first)
     * @param removed rules dropped because {@code replace} cleared memory first (0 when merging)
     */
    public record ImportResult(int imported, int created, int updated, int removed, boolean replaced) {}

    /** Carries every problem found, by line, so the whole file can be fixed in one pass. */
    public static class ImportException extends Exception {
        public final List<String> errors;

        ImportException(List<String> errors) {
            super(String.join(" ", errors));
            this.errors = errors;
        }
    }

    public List<ImportedRule> parse(InputStream input) throws IOException, ImportException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .get();

        // Category names are matched case-insensitively but stored as the instance spells them,
        // so "groceries" in a hand-edited file still lands on the real "Groceries" category.
        Map<String, String> categoryByLowerName = new HashMap<>();
        for (String name : categories.findAllNames()) {
            categoryByLowerName.put(name.toLowerCase(Locale.ROOT), name);
        }

        List<ImportedRule> rules = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenBands = new HashSet<>();

        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            // The export writes a byte-order mark for Excel's sake; it arrives glued to the first
            // header name, so match headers with it stripped or a round trip would fail on its own file.
            Map<String, String> headerByName = new HashMap<>();
            for (String header : parser.getHeaderNames()) {
                headerByName.put(header.replace(BYTE_ORDER_MARK, "").trim().toLowerCase(Locale.ROOT), header);
            }
            if (!headerByName.containsKey("merchant_key") || !headerByName.containsKey("category")) {
                throw new ImportException(List.of(
                        "The file needs merchant_key and category columns. Found: "
                                + String.join(", ", parser.getHeaderNames()) + "."));
            }

            for (CSVRecord record : parser) {
                // +1 for the header row, so the number matches what a spreadsheet shows.
                long line = record.getRecordNumber() + 1;
                String key = value(record, headerByName, "merchant_key").toUpperCase(Locale.ROOT);
                String rawCategory = value(record, headerByName, "category");
                String rawMin = value(record, headerByName, "min_amount");
                String rawMax = value(record, headerByName, "max_amount");
                String rawSource = value(record, headerByName, "source").toLowerCase(Locale.ROOT);

                if (key.isEmpty() && rawCategory.isEmpty()) continue; // a blank row a spreadsheet left behind

                List<String> rowErrors = new ArrayList<>();
                if (key.isEmpty()) rowErrors.add("merchant_key is empty");

                String category = categoryByLowerName.get(rawCategory.toLowerCase(Locale.ROOT));
                if (category == null) {
                    rowErrors.add(rawCategory.isEmpty()
                            ? "category is empty"
                            : "category \"" + rawCategory + "\" doesn't exist here — create it first");
                }

                Double min = rawMin.isEmpty() ? Double.valueOf(0) : parseAmount(rawMin);
                // Blank means "no upper bound", the same way the export writes a catch-all.
                Double max = rawMax.isEmpty() ? Double.valueOf(MerchantCategory.UNBOUNDED) : parseAmount(rawMax);
                if (min == null) rowErrors.add("min_amount \"" + rawMin + "\" isn't a number");
                if (max == null) rowErrors.add("max_amount \"" + rawMax + "\" isn't a number");
                if (min != null && min < 0) rowErrors.add("min_amount can't be negative");
                if (min != null && max != null && min >= max) rowErrors.add("min_amount must be less than max_amount");

                // An unknown or blank source is treated as a correction: whoever wrote the file
                // decided on this category, which is what 'user' means.
                String source = MerchantCategory.SOURCE_AI.equals(rawSource)
                        ? MerchantCategory.SOURCE_AI
                        : MerchantCategory.SOURCE_USER;

                if (rowErrors.isEmpty() && !seenBands.add(key + "|" + min + "|" + max)) {
                    rowErrors.add("duplicates an earlier row for the same merchant and amount band");
                }

                if (!rowErrors.isEmpty()) {
                    errors.add("Line " + line + ": " + String.join("; ", rowErrors) + ".");
                } else {
                    rules.add(new ImportedRule(key, category, min, max, source));
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Commons CSV's way of saying the file isn't CSV it can read (duplicate headers, etc.).
            throw new ImportException(List.of("Couldn't read the file as CSV: " + e.getMessage()));
        }

        if (!errors.isEmpty()) {
            List<String> shown = new ArrayList<>(errors.subList(0, Math.min(MAX_REPORTED_ERRORS, errors.size())));
            if (errors.size() > MAX_REPORTED_ERRORS) {
                shown.add("…and " + (errors.size() - MAX_REPORTED_ERRORS) + " more.");
            }
            throw new ImportException(shown);
        }
        if (rules.isEmpty()) {
            throw new ImportException(List.of("The file has no merchant rules in it."));
        }
        return rules;
    }

    /**
     * Writes validated rules. Merging keeps every rule the file doesn't mention and overwrites any
     * the file does (same merchant and band) -- the file is the newer, deliberate statement.
     * Replacing clears memory first, so afterwards it holds exactly what the file does.
     *
     * <p>Hit counts start at zero for every imported rule, existing or not: they count how often
     * a rule answered for <em>this</em> instance's transactions, which a file can't know.
     */
    @Transactional
    public ImportResult apply(List<ImportedRule> rules, boolean replace) {
        int removed = replace ? merchants.deleteAll() : 0;

        Set<String> existingBands = new HashSet<>();
        if (!replace) {
            for (MerchantCategory m : merchants.findAll()) {
                existingBands.add(m.merchantKey() + "|" + m.minAmount() + "|" + m.maxAmount());
            }
        }

        int created = 0;
        int updated = 0;
        for (ImportedRule rule : rules) {
            if (existingBands.contains(rule.merchantKey() + "|" + rule.minAmount() + "|" + rule.maxAmount())) {
                updated++;
            } else {
                created++;
            }
            merchants.importRule(rule.merchantKey(), rule.category(), rule.minAmount(), rule.maxAmount(), rule.source());
        }
        return new ImportResult(rules.size(), created, updated, removed, replace);
    }

    private static String value(CSVRecord record, Map<String, String> headerByName, String name) {
        String header = headerByName.get(name);
        if (header == null || !record.isSet(header)) return "";
        return record.get(header).trim();
    }

    private static Double parseAmount(String raw) {
        try {
            double v = Double.parseDouble(raw.replace(",", ""));
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
