package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionEditTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private MerchantCategoryRepository merchants;

    private long transactionId;

    @BeforeEach
    void seed() {
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-10", "EDIT ME LTD 4321", 42.00, "debit", null)),
                "edit-test-batch", 1L);

        transactionId = transactions.find(null, null, null, DateRange.ALL, 200, 0).stream()
                .filter(t -> t.uploadBatchId().equals("edit-test-batch"))
                .findFirst().orElseThrow().id();
    }

    /** Jackson would give us a Map; build one the same shape a request body produces. */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private Transaction reload() {
        return transactions.findById(transactionId).orElseThrow();
    }

    // --- editing ----------------------------------------------------------------

    @Test
    @DisplayName("edits the fields supplied and leaves the rest alone")
    void editsOnlyWhatIsSupplied() {
        ResponseEntity<?> response = controller.update(transactionId, body("amount", 99.95));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Transaction updated = reload();
        assertThat(updated.amount()).isEqualTo(99.95);
        assertThat(updated.description()).isEqualTo("EDIT ME LTD 4321");  // untouched
        assertThat(updated.date()).isEqualTo("2026-06-10");               // untouched
    }

    @Test
    @DisplayName("can change several fields at once")
    void editsMultipleFields() {
        controller.update(transactionId, body(
                "date", "2026-06-11",
                "description", "CORRECTED NAME",
                "amount", 12.34,
                "type", "credit"));

        Transaction updated = reload();
        assertThat(updated.date()).isEqualTo("2026-06-11");
        assertThat(updated.description()).isEqualTo("CORRECTED NAME");
        assertThat(updated.amount()).isEqualTo(12.34);
        assertThat(updated.type()).isEqualTo("credit");
    }

    @Test
    @DisplayName("changing the category still teaches merchant memory")
    void categoryChangeTeachesMemory() {
        controller.update(transactionId, body("category", "Groceries"));

        assertThat(reload().categorySource()).isEqualTo("user");
        // Store number stripped, matching how memory keys merchants.
        assertThat(merchants.findByKey("EDIT ME LTD")).isPresent()
                .get().extracting("category").isEqualTo("Groceries");
    }

    @Test
    @DisplayName("renaming and recategorising together keys memory on the new name")
    void memoryUsesTheNewDescription() {
        controller.update(transactionId, body(
                "description", "PROPER MERCHANT NAME",
                "category", "Shopping"));

        assertThat(merchants.findByKey("PROPER MERCHANT NAME")).isPresent();
        assertThat(merchants.findByKey("EDIT ME LTD")).isEmpty();
    }

    // --- validation -------------------------------------------------------------

    @Test
    @DisplayName("rejects a non-positive amount")
    void rejectsNonPositiveAmount() {
        // Amounts are stored unsigned with direction in `type`; a negative here would
        // quietly corrupt every total that sums the column.
        assertThat(controller.update(transactionId, body("amount", -5.0)).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.update(transactionId, body("amount", 0.0)).getStatusCode().value())
                .isEqualTo(400);
        assertThat(reload().amount()).isEqualTo(42.00);
    }

    @Test
    @DisplayName("rejects a malformed date")
    void rejectsMalformedDate() {
        assertThat(controller.update(transactionId, body("date", "10/06/2026")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(reload().date()).isEqualTo("2026-06-10");
    }

    @Test
    @DisplayName("rejects an empty description and an unknown type")
    void rejectsEmptyDescriptionAndBadType() {
        assertThat(controller.update(transactionId, body("description", "   ")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.update(transactionId, body("type", "refund")).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an unknown category")
    void rejectsUnknownCategory() {
        assertThat(controller.update(transactionId, body("category", "Nonsense")).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a request that changes nothing")
    void rejectsEmptyUpdate() {
        assertThat(controller.update(transactionId, body()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("editing a transaction that does not exist is a 404")
    void editingMissingTransactionIs404() {
        assertThat(controller.update(999_999L, body("amount", 1.0)).getStatusCode().value())
                .isEqualTo(404);
    }

    // --- deleting ---------------------------------------------------------------

    @Test
    @DisplayName("deletes a transaction")
    void deletesTransaction() {
        assertThat(controller.delete(transactionId).getStatusCode().value()).isEqualTo(200);
        assertThat(transactions.findById(transactionId)).isEmpty();
    }

    @Test
    @DisplayName("deleting a transaction that does not exist is a 404")
    void deletingMissingTransactionIs404() {
        assertThat(controller.delete(999_999L).getStatusCode().value()).isEqualTo(404);
    }
}
