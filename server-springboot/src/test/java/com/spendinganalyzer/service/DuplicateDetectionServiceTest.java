package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectionServiceTest {

    private final DuplicateDetectionService service = new DuplicateDetectionService();

    private static ParsedTransaction tx(String date, String description, double amount) {
        return new ParsedTransaction(date, description, amount, "debit", null);
    }

    private Map<String, Integer> existing(ParsedTransaction... transactions) {
        return java.util.Arrays.stream(transactions)
                .collect(java.util.stream.Collectors.toMap(
                        ParsedTransaction::dedupeKey, t -> 1, Integer::sum));
    }

    @Test
    @DisplayName("imports everything when the account is empty")
    void importsAllWhenNothingExists() {
        List<ParsedTransaction> incoming = List.of(
                tx("2026-05-01", "NETFLIX.COM", 15.49),
                tx("2026-05-02", "SPOTIFY", 10.99));

        var result = service.filterDuplicates(incoming, Map.of());

        assertThat(result.toInsert()).hasSize(2);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("skips rows already present — re-uploading the same file imports nothing")
    void reuploadingSameFileIsIdempotent() {
        List<ParsedTransaction> incoming = List.of(
                tx("2026-05-01", "NETFLIX.COM", 15.49),
                tx("2026-05-02", "SPOTIFY", 10.99));

        var result = service.filterDuplicates(incoming, existing(incoming.toArray(ParsedTransaction[]::new)));

        assertThat(result.toInsert()).isEmpty();
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("imports only the new rows from an overlapping statement")
    void importsOnlyTheNewRowsFromAnOverlappingRange() {
        ParsedTransaction alreadyHave = tx("2026-05-01", "NETFLIX.COM", 15.49);
        List<ParsedTransaction> incoming = List.of(
                alreadyHave,
                tx("2026-06-01", "NETFLIX.COM", 15.49));

        var result = service.filterDuplicates(incoming, existing(alreadyHave));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.toInsert()).singleElement()
                .extracting(ParsedTransaction::date).isEqualTo("2026-06-01");
    }

    @Test
    @DisplayName("two identical same-day purchases are kept, not collapsed into one")
    void legitimateSameDayRepeatsAreNotTreatedAsDuplicates() {
        // Buying the same coffee twice in one day is real, not a double import.
        ParsedTransaction coffee = tx("2026-05-01", "STARBUCKS", 5.75);
        List<ParsedTransaction> incoming = List.of(coffee, coffee);

        var result = service.filterDuplicates(incoming, Map.of());

        assertThat(result.toInsert()).hasSize(2);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("counts are compared, so a file with two imports only the surplus over one")
    void importsSurplusWhenFileHasMoreCopiesThanTheDatabase() {
        ParsedTransaction coffee = tx("2026-05-01", "STARBUCKS", 5.75);
        List<ParsedTransaction> incoming = List.of(coffee, coffee, coffee);

        // Database already holds one of the three.
        var result = service.filterDuplicates(incoming, Map.of(coffee.dedupeKey(), 1));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.toInsert()).hasSize(2);
    }

    @Test
    @DisplayName("a different amount or date is a different transaction")
    void nearMissesAreNotDuplicates() {
        ParsedTransaction original = tx("2026-05-01", "NETFLIX.COM", 15.49);
        List<ParsedTransaction> incoming = List.of(
                tx("2026-05-01", "NETFLIX.COM", 16.49),   // price change
                tx("2026-05-02", "NETFLIX.COM", 15.49));  // different day

        var result = service.filterDuplicates(incoming, existing(original));

        assertThat(result.toInsert()).hasSize(2);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("description matching ignores case and surrounding whitespace")
    void descriptionMatchingIsNormalised() {
        ParsedTransaction stored = tx("2026-05-01", "netflix.com", 15.49);
        List<ParsedTransaction> incoming = List.of(tx("2026-05-01", "  NETFLIX.COM  ", 15.49));

        var result = service.filterDuplicates(incoming, existing(stored));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.toInsert()).isEmpty();
    }

    @Test
    @DisplayName("a debit and a credit of the same amount are distinct")
    void debitAndCreditAreDistinct() {
        ParsedTransaction debit = new ParsedTransaction("2026-05-01", "ACME", 50.0, "debit", null);
        ParsedTransaction credit = new ParsedTransaction("2026-05-01", "ACME", 50.0, "credit", null);

        var result = service.filterDuplicates(List.of(credit), existing(debit));

        assertThat(result.toInsert()).hasSize(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("the caller's existing-counts map is not mutated")
    void doesNotMutateCallerState() {
        ParsedTransaction t = tx("2026-05-01", "NETFLIX.COM", 15.49);
        Map<String, Integer> counts = new java.util.HashMap<>(Map.of(t.dedupeKey(), 1));

        service.filterDuplicates(List.of(t), counts);

        assertThat(counts).containsEntry(t.dedupeKey(), 1);
    }

    @Test
    @DisplayName("date bounds cover the whole batch")
    void reportsDateRangeOfBatch() {
        List<ParsedTransaction> incoming = List.of(
                tx("2026-06-15", "B", 1.0),
                tx("2026-05-01", "A", 1.0),
                tx("2026-07-30", "C", 1.0));

        assertThat(service.minDate(incoming)).isEqualTo("2026-05-01");
        assertThat(service.maxDate(incoming)).isEqualTo("2026-07-30");
    }
}
