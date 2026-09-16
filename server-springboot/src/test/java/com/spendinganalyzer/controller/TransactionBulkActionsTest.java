package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
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

/** Categorizing, tagging, and deleting several transactions in one request. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionBulkActionsTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private MerchantCategoryRepository merchants;

    @Autowired
    private TagRepository tags;

    private long id1;
    private long id2;
    private long id3;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "COFFEE SHOP A", 4.50, "debit", null),
                new ParsedTransaction("2026-06-02", "COFFEE SHOP B", 5.00, "debit", null),
                new ParsedTransaction("2026-06-03", "GROCERY STORE", 60.00, "debit", null)
        ), "bulk-test-batch", 1L);

        List<Transaction> rows = transactions.find(null, null, null, DateRange.ALL, 10, 0);
        id1 = rows.stream().filter(t -> t.description().equals("COFFEE SHOP A")).findFirst().orElseThrow().id();
        id2 = rows.stream().filter(t -> t.description().equals("COFFEE SHOP B")).findFirst().orElseThrow().id();
        id3 = rows.stream().filter(t -> t.description().equals("GROCERY STORE")).findFirst().orElseThrow().id();
    }

    // --- bulk category ------------------------------------------------------------

    @Test
    @DisplayName("categorizes every selected transaction, and teaches merchant memory for each distinct merchant")
    void bulkCategorizeTeachesMemory() {
        ResponseEntity<?> response = controller.bulkCategory(
                Map.of("ids", List.of(id1, id2, id3), "category", "Dining & Coffee"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) response.getBody()).get("updated")).isEqualTo(3);
        assertThat(transactions.findById(id1)).get().extracting(Transaction::category).isEqualTo("Dining & Coffee");
        assertThat(transactions.findById(id2)).get().extracting(Transaction::category).isEqualTo("Dining & Coffee");
        assertThat(transactions.findById(id3)).get().extracting(Transaction::category).isEqualTo("Dining & Coffee");
        assertThat(merchants.findByKey("COFFEE SHOP A")).isNotEmpty();
        assertThat(merchants.findByKey("COFFEE SHOP B")).isNotEmpty();
        assertThat(merchants.findByKey("GROCERY STORE")).isNotEmpty();
    }

    @Test
    @DisplayName("a shared merchant among the selection is only taught once")
    void sharedMerchantTaughtOnce() {
        // Both share a description, so both normalize to the same merchant key.
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-04", "SAME MERCHANT", 10.00, "debit", null),
                new ParsedTransaction("2026-06-05", "SAME MERCHANT", 12.00, "debit", null)
        ), "dup-merchant-batch", 1L);
        List<Transaction> rows = transactions.find(null, null, null, DateRange.ALL, 10, 0).stream()
                .filter(t -> t.description().equals("SAME MERCHANT")).toList();

        controller.bulkCategory(Map.of(
                "ids", List.of(rows.get(0).id(), rows.get(1).id()), "category", "Groceries"));

        assertThat(merchants.findByKey("SAME MERCHANT")).hasSize(1);
    }

    @Test
    @DisplayName("rejects an empty id list and an unknown category")
    void rejectsInvalidBulkCategoryInput() {
        assertThat(controller.bulkCategory(Map.of("ids", List.of(), "category", "Groceries"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.bulkCategory(Map.of("ids", List.of(id1), "category", "Nonsense"))
                .getStatusCode().value()).isEqualTo(400);
    }

    // --- bulk tags ------------------------------------------------------------------

    @Test
    @DisplayName("tags every selected transaction, creating the tag once")
    void bulkTagsEverySelected() {
        ResponseEntity<?> response = controller.bulkAddTag(
                Map.of("ids", List.of(id1, id2, id3), "name", "coffee run"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) response.getBody()).get("tagged")).isEqualTo(3);
        assertThat(tags.namesFor(id1)).containsExactly("coffee run");
        assertThat(tags.namesFor(id2)).containsExactly("coffee run");
        assertThat(tags.namesFor(id3)).containsExactly("coffee run");
        assertThat(tags.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("rejects an empty id list and a blank tag name")
    void rejectsInvalidBulkTagInput() {
        assertThat(controller.bulkAddTag(Map.of("ids", List.of(), "name", "x"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.bulkAddTag(Map.of("ids", List.of(id1), "name", "  "))
                .getStatusCode().value()).isEqualTo(400);
    }

    // --- bulk delete ------------------------------------------------------------------

    @Test
    @DisplayName("deletes every selected transaction, leaving the rest untouched")
    void bulkDeletesSelected() {
        ResponseEntity<?> response = controller.bulkDelete(Map.of("ids", List.of(id1, id2)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) response.getBody()).get("deleted")).isEqualTo(2);
        assertThat(transactions.findById(id1)).isEmpty();
        assertThat(transactions.findById(id2)).isEmpty();
        assertThat(transactions.findById(id3)).isPresent();
    }

    @Test
    @DisplayName("rejects an empty id list")
    void rejectsEmptyBulkDelete() {
        assertThat(controller.bulkDelete(Map.of("ids", List.of())).getStatusCode().value()).isEqualTo(400);
    }
}
