package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The `search` filter on the transactions list — a case-insensitive substring match on description. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionSearchTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "UBER TRIP 12345", 15.00, "debit", "Transportation"),
                new ParsedTransaction("2026-06-02", "UBER EATS", 32.50, "debit", "Dining & Coffee"),
                new ParsedTransaction("2026-06-03", "GROCERY STORE", 60.00, "debit", "Groceries"),
                new ParsedTransaction("2026-06-04", "50% OFF STORE", 10.00, "debit", "Shopping")
        ), "search-test-batch", 1L);
    }

    @Test
    @DisplayName("matches a substring of the description, case-insensitively")
    void matchesSubstringCaseInsensitively() {
        var result = controller.list(null, null, null, null, null, null, "uber", 200, 0);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.transactions()).extracting(t -> t.transaction().description())
                .containsExactlyInAnyOrder("UBER TRIP 12345", "UBER EATS");
    }

    @Test
    @DisplayName("a term with no match returns nothing")
    void noMatchReturnsNothing() {
        var result = controller.list(null, null, null, null, null, null, "no such word", 200, 0);

        assertThat(result.total()).isZero();
        assertThat(result.transactions()).isEmpty();
    }

    @Test
    @DisplayName("a literal % or _ in the search term is escaped, not treated as a wildcard")
    void escapesLikeWildcards() {
        var percentMatch = controller.list(null, null, null, null, null, null, "50%", 200, 0);
        assertThat(percentMatch.transactions()).extracting(t -> t.transaction().description())
                .containsExactly("50% OFF STORE");

        // Without escaping, "%" alone would match every row rather than requiring a literal percent sign.
        var noSuchPercent = controller.list(null, null, null, null, null, null, "99%", 200, 0);
        assertThat(noSuchPercent.transactions()).isEmpty();
    }

    @Test
    @DisplayName("combines with the category filter")
    void combinesWithCategoryFilter() {
        var result = controller.list("Dining & Coffee", null, null, null, null, null, "uber", 200, 0);

        assertThat(result.transactions()).extracting(t -> t.transaction().description())
                .containsExactly("UBER EATS");
    }

    @Test
    @DisplayName("the count matches the list size")
    void countMatchesList() {
        assertThat(transactions.count(null, null, null, DateRange.ALL, null, "uber")).isEqualTo(2);
        assertThat(transactions.find(null, null, null, DateRange.ALL, null, "uber", 200, 0)).hasSize(2);
    }
}
