package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionReceiptRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Attaching, viewing, and removing a receipt image or PDF on a transaction. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionReceiptTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TransactionReceiptRepository receipts;

    private long transactionId;
    private long otherTransactionId;

    private static MockMultipartFile jpeg(String content) {
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", content.getBytes());
    }

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "OFFICE SUPPLIES", 42.00, "debit", null),
                new ParsedTransaction("2026-06-02", "HARDWARE STORE", 18.00, "debit", null)
        ), "receipt-endpoint-batch", 1L);

        var rows = transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 10, 0);
        transactionId = rows.stream().filter(t -> t.description().equals("OFFICE SUPPLIES")).findFirst().orElseThrow().id();
        otherTransactionId = rows.stream().filter(t -> t.description().equals("HARDWARE STORE")).findFirst().orElseThrow().id();
    }

    // --- uploading -----------------------------------------------------------------

    @Test
    @DisplayName("attaches a receipt to a transaction")
    void attachesReceipt() {
        ResponseEntity<?> response = controller.uploadReceipt(transactionId, jpeg("fake-image-bytes"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(receipts.find(transactionId)).isPresent().get()
                .satisfies(r -> {
                    assertThat(r.filename()).isEqualTo("receipt.jpg");
                    assertThat(r.contentType()).isEqualTo("image/jpeg");
                    assertThat(r.data()).isEqualTo("fake-image-bytes".getBytes());
                });
    }

    @Test
    @DisplayName("uploading to a missing transaction is a 404")
    void uploadingToMissingTransactionIs404() {
        assertThat(controller.uploadReceipt(999_999L, jpeg("x")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rejects an unsupported content type")
    void rejectsUnsupportedContentType() {
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        ResponseEntity<?> response = controller.uploadReceipt(transactionId, file);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(receipts.find(transactionId)).isEmpty();
    }

    @Test
    @DisplayName("rejects an empty file")
    void rejectsEmptyFile() {
        var empty = new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[0]);

        assertThat(controller.uploadReceipt(transactionId, empty).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("re-uploading replaces the existing receipt rather than adding a second one")
    void reuploadingReplaces() {
        controller.uploadReceipt(transactionId, jpeg("first"));
        controller.uploadReceipt(transactionId, jpeg("second"));

        assertThat(receipts.find(transactionId)).isPresent().get()
                .extracting(r -> new String(r.data())).isEqualTo("second");
    }

    // --- downloading -----------------------------------------------------------------

    @Test
    @DisplayName("streams the receipt back with its content type")
    void downloadsReceipt() {
        controller.uploadReceipt(transactionId, jpeg("fake-image-bytes"));

        ResponseEntity<?> response = controller.getReceipt(transactionId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getBody()).isEqualTo("fake-image-bytes".getBytes());
    }

    @Test
    @DisplayName("downloading when there is no receipt is a 404")
    void downloadingMissingReceiptIs404() {
        assertThat(controller.getReceipt(transactionId).getStatusCode().value()).isEqualTo(404);
    }

    // --- deleting -----------------------------------------------------------------

    @Test
    @DisplayName("removes a receipt, and reports a missing one as 404")
    void deletesReceipt() {
        controller.uploadReceipt(transactionId, jpeg("x"));

        assertThat(controller.deleteReceipt(transactionId).getStatusCode().value()).isEqualTo(200);
        assertThat(receipts.find(transactionId)).isEmpty();
        assertThat(controller.deleteReceipt(transactionId).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleting a transaction removes its receipt too")
    void deletingTransactionRemovesReceipt() {
        controller.uploadReceipt(transactionId, jpeg("x"));

        controller.delete(transactionId);

        assertThat(receipts.find(transactionId)).isEmpty();
    }

    @Test
    @DisplayName("bulk-deleting transactions removes their receipts too")
    void bulkDeletingTransactionsRemovesReceipts() {
        controller.uploadReceipt(transactionId, jpeg("x"));
        controller.uploadReceipt(otherTransactionId, jpeg("y"));

        controller.bulkDelete(Map.of("ids", List.of(transactionId, otherTransactionId)));

        assertThat(receipts.find(transactionId)).isEmpty();
        assertThat(receipts.find(otherTransactionId)).isEmpty();
    }

    @Test
    @DisplayName("resetting all data clears receipts too")
    void resetClearsReceipts() {
        controller.uploadReceipt(transactionId, jpeg("x"));

        controller.reset();

        assertThat(receipts.find(transactionId)).isEmpty();
    }

    // --- listing -----------------------------------------------------------------

    @Test
    @DisplayName("the transactions list flags which rows have a receipt")
    void listFlagsHasReceipt() {
        controller.uploadReceipt(transactionId, jpeg("x"));

        var all = controller.list(null, null, null, null, null, null, null, 200, 0);

        var withReceipt = all.transactions().stream()
                .filter(t -> t.transaction().id() == transactionId).findFirst().orElseThrow();
        var withoutReceipt = all.transactions().stream()
                .filter(t -> t.transaction().id() == otherTransactionId).findFirst().orElseThrow();
        assertThat(withReceipt.hasReceipt()).isTrue();
        assertThat(withoutReceipt.hasReceipt()).isFalse();
    }
}
