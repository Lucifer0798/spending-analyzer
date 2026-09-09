package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.PredictionsCacheRepository;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
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

/**
 * Proves a restore genuinely replaces state rather than adding to it -- the whole reason
 * import is a restore and not a merge (see {@link com.spendinganalyzer.controller.BackupController}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackupServiceTest {

    @Autowired
    private BackupService backupService;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private MerchantCategoryRepository merchants;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private RecurringOverrideRepository recurringOverrides;

    @Autowired
    private PredictionsCacheRepository predictionsCache;

    @BeforeEach
    void seed() {
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-01", "COFFEE SHOP", 4.50, "debit", "Dining & Coffee")),
                "backup-test-batch", Account.DEFAULT_ID);
        merchants.remember("COFFEE SHOP", "Dining & Coffee", "user");
        budgets.upsert("Dining & Coffee", 100.0);
        recurringOverrides.upsert("NETFLIX.COM", RecurringOverride.ACTION_CANCEL);
        predictionsCache.upsert(Account.DEFAULT_ID, "{\"summary\":\"s\"}", "2026-06-01T00:00:00Z");
    }

    @Test
    @DisplayName("exports every table a backup covers, at the current version")
    void exportsEverything() {
        BackupData data = backupService.export();

        assertThat(data.version()).isEqualTo(BackupData.CURRENT_VERSION);
        assertThat(data.accounts()).extracting("name").contains("Default");
        assertThat(data.transactions()).extracting("description").contains("COFFEE SHOP");
        assertThat(data.merchantCategories()).extracting("merchantKey").contains("COFFEE SHOP");
        assertThat(data.budgets()).extracting("category").contains("Dining & Coffee");
        assertThat(data.recurringOverrides()).extracting("merchantKey").contains("NETFLIX.COM");
        // Built-in categories are exported too, not just custom ones.
        assertThat(data.categories()).extracting("name").contains("Groceries");
    }

    @Test
    @DisplayName("restoring an earlier export undoes everything added after it, not just adds the backup on top")
    void restoreReplacesRatherThanMerges() {
        BackupData snapshot = backupService.export();

        // Everything below happens after the snapshot was taken.
        Account extraAccount = accounts.create("Extra Account", "checking", "USD");
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-07-01", "EXTRA CHARGE", 20.00, "debit", "Shopping")),
                "post-snapshot-batch", extraAccount.id());
        budgets.upsert("Shopping", 200.0);

        backupService.restore(snapshot);

        assertThat(accounts.findAll(true)).extracting("name").containsExactly("Default");
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 100, 0))
                .extracting("description").containsExactly("COFFEE SHOP");
        assertThat(budgets.findAll()).extracting("category").containsExactly("Dining & Coffee");
    }

    @Test
    @DisplayName("restoring clears predictions_cache even though it isn't part of the backup")
    void restoreClearsPredictionsCache() {
        BackupData snapshot = backupService.export();
        assertThat(predictionsCache.find(Account.DEFAULT_ID)).isPresent();

        backupService.restore(snapshot);

        assertThat(predictionsCache.find(Account.DEFAULT_ID)).isEmpty();
    }

    @Test
    @DisplayName("restore reports how many rows of each kind it loaded")
    void restoreReportsCounts() {
        BackupData snapshot = backupService.export();

        BackupService.BackupSummary summary = backupService.restore(snapshot);

        assertThat(summary.accounts()).isEqualTo(snapshot.accounts().size());
        assertThat(summary.transactions()).isEqualTo(snapshot.transactions().size());
        assertThat(summary.categories()).isEqualTo(snapshot.categories().size());
        assertThat(summary.budgets()).isEqualTo(snapshot.budgets().size());
        assertThat(summary.merchantCategories()).isEqualTo(snapshot.merchantCategories().size());
        assertThat(summary.recurringOverrides()).isEqualTo(snapshot.recurringOverrides().size());
    }

    @Test
    @DisplayName("restoring an empty backup wipes every table it covers down to nothing")
    void restoringEmptyBackupWipesEverything() {
        BackupData empty = new BackupData(BackupData.CURRENT_VERSION, "2026-01-01T00:00:00Z",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        backupService.restore(empty);

        assertThat(accounts.findAll(true)).isEmpty();
        assertThat(categories.findAll()).isEmpty();
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 100, 0)).isEmpty();
        assertThat(merchants.findAll()).isEmpty();
        assertThat(budgets.findAll()).isEmpty();
        assertThat(recurringOverrides.findAll()).isEmpty();
    }
}
