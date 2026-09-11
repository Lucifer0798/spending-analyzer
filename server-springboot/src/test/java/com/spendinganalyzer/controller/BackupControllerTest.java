package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackupControllerTest {

    @Autowired
    private BackupController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @BeforeEach
    void seed() {
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-01", "BACKUP FIXTURE", 10.00, "debit", "Groceries")),
                "backup-controller-batch", 1L);
    }

    @Test
    @DisplayName("serves a dated .json attachment")
    void exportsAttachment() {
        ResponseEntity<BackupData> response = controller.export();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("spending-analyzer-backup-");
        assertThat(response.getBody().transactions()).extracting("description").contains("BACKUP FIXTURE");
    }

    @Test
    @DisplayName("round-trips through export and import")
    void roundTripsThroughExportAndImport() {
        BackupData exported = controller.export().getBody();

        accounts.create("Something Added Later", "checking", "USD");

        ResponseEntity<?> response = controller.restore(exported);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(accounts.findAll(true)).extracting("name").containsExactly("Default");
    }

    @Test
    @DisplayName("rejects a backup from an unsupported version")
    void rejectsUnsupportedVersion() {
        BackupData exported = controller.export().getBody();
        BackupData wrongVersion = new BackupData(
                99, exported.exportedAt(), exported.accounts(), exported.categories(),
                exported.transactions(), exported.merchantCategories(), exported.budgets(),
                exported.recurringOverrides(), exported.goals(), exported.goalContributions(),
                exported.tags(), exported.transactionTags());

        ResponseEntity<?> response = controller.restore(wrongVersion);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        // Nothing was touched -- the fixture transaction from @BeforeEach is still there.
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 100, 0))
                .extracting("description").contains("BACKUP FIXTURE");
    }
}
