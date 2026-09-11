package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tagging and untagging a transaction, and the tag filter on the transactions list. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionTagsTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TagRepository tags;

    private long taggedId;
    private long untaggedId;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "FLIGHT", 200.00, "debit", null),
                new ParsedTransaction("2026-06-02", "GROCERIES", 50.00, "debit", null)
        ), "tag-endpoint-batch", 1L);

        var rows = transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 10, 0);
        taggedId = rows.stream().filter(t -> t.description().equals("FLIGHT")).findFirst().orElseThrow().id();
        untaggedId = rows.stream().filter(t -> t.description().equals("GROCERIES")).findFirst().orElseThrow().id();
    }

    @Test
    @DisplayName("adds a tag, creating it if needed")
    @SuppressWarnings("unchecked")
    void addsTag() {
        ResponseEntity<?> response = controller.addTag(taggedId, Map.of("name", "business trip"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<String>) ((Map<String, Object>) response.getBody()).get("tags"))
                .containsExactly("business trip");
    }

    @Test
    @DisplayName("rejects a blank tag name")
    void rejectsBlankTagName() {
        assertThat(controller.addTag(taggedId, Map.of("name", "  ")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("adding a tag to a missing transaction is a 404")
    void addingTagToMissingTransactionIs404() {
        assertThat(controller.addTag(999_999L, Map.of("name", "x")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("removes a tag, leaving the tag itself for other transactions")
    @SuppressWarnings("unchecked")
    void removesTag() {
        tags.addTag(taggedId, "business trip");
        tags.addTag(untaggedId, "business trip");

        ResponseEntity<?> response = controller.removeTag(taggedId, "business trip");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<String>) ((Map<String, Object>) response.getBody()).get("tags")).isEmpty();
        assertThat(tags.namesFor(untaggedId)).containsExactly("business trip");
    }

    @Test
    @DisplayName("removing a tag from a missing transaction is a 404")
    void removingTagFromMissingTransactionIs404() {
        assertThat(controller.removeTag(999_999L, "x").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the transactions list carries each row's tags, and the tag filter narrows it")
    void listCarriesTagsAndFilters() {
        tags.addTag(taggedId, "business trip");

        var all = controller.list(null, null, null, null, null, null, 200, 0);
        assertThat(all.transactions()).hasSize(2);
        var flight = all.transactions().stream()
                .filter(t -> t.transaction().description().equals("FLIGHT")).findFirst().orElseThrow();
        var groceries = all.transactions().stream()
                .filter(t -> t.transaction().description().equals("GROCERIES")).findFirst().orElseThrow();
        assertThat(flight.tags()).containsExactly("business trip");
        assertThat(groceries.tags()).isEmpty();

        var filtered = controller.list(null, null, null, null, null, "business trip", 200, 0);
        assertThat(filtered.transactions()).extracting(t -> t.transaction().description())
                .containsExactly("FLIGHT");
        assertThat(filtered.total()).isEqualTo(1);
    }
}
