package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.InsightsController;
import com.spendinganalyzer.controller.MerchantNameController;
import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantNameRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.MerchantNameService.Merchant;
import com.spendinganalyzer.service.MerchantNameService.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Naming merchants, and merging look-alikes under one name -- and what a name changes elsewhere. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MerchantNameServiceTest {

    @Autowired private MerchantNameService service;
    @Autowired private MerchantNameController api;
    @Autowired private MerchantNameRepository names;
    @Autowired private StatsService stats;
    @Autowired private InsightsController insights;
    @Autowired private DuplicateChargeService duplicates;
    @Autowired private TransactionRepository transactions;
    @Autowired private TransactionController transactionsApi;
    @Autowired private BackupService backups;

    private void seed(ParsedTransaction... rows) {
        transactions.insertBatch(List.of(rows), "merchant-names-test", Account.DEFAULT_ID);
    }

    private static ParsedTransaction debit(String date, String description, double amount) {
        return new ParsedTransaction(date, description, amount, "debit", "Shopping");
    }

    private void seedAmazon() {
        seed(debit("2026-03-02", "AMZN MKTP US*2K4", 30),
                debit("2026-03-09", "AMZN MKTP US*9QX", 20),
                debit("2026-03-15", "AMAZON.COM*AB123", 50),
                debit("2026-03-20", "AMAZON MKTP US 5521", 10),
                debit("2026-03-21", "CORNER CAFE", 4));
    }

    private Merchant merchant(String key) {
        return service.list().merchants().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("merging gives several spellings one name: top merchants counts them as one place")
    void mergeCombinesTopMerchants() {
        seedAmazon();
        assertThat(stats.computeMerchantTotals(Account.DEFAULT_ID, DateRange.ALL, null))
                .extracting(MerchantTotal::merchant)
                .contains("AMZN MKTP US", "AMAZON.COM", "AMAZON MKTP US");

        service.rename(List.of("AMZN MKTP US", "AMAZON.COM", "AMAZON MKTP US"), "Amazon");

        List<MerchantTotal> totals = stats.computeMerchantTotals(Account.DEFAULT_ID, DateRange.ALL, null);
        assertThat(totals).extracting(MerchantTotal::merchant).containsExactly("Amazon", "CORNER CAFE");
        MerchantTotal amazon = totals.get(0);
        assertThat(amazon.total()).isEqualTo(110);
        assertThat(amazon.count()).isEqualTo(4);
        assertThat(amazon.firstDate()).isEqualTo("2026-03-02");
        assertThat(amazon.lastDate()).isEqualTo("2026-03-20");

        // Taking one name off splits that spelling back out.
        service.clear(List.of("AMAZON MKTP US"));
        assertThat(stats.computeMerchantTotals(Account.DEFAULT_ID, DateRange.ALL, null))
                .extracting(MerchantTotal::merchant).containsExactly("Amazon", "AMAZON MKTP US", "CORNER CAFE");
    }

    @Test
    @DisplayName("searching a name finds every spelling under it, alongside plain text matches")
    void searchFindsByName() {
        seedAmazon();
        seed(debit("2026-03-22", "AMAZING THAI", 15));
        service.rename(List.of("AMZN MKTP US", "AMAZON.COM"), "Amazon");

        List<Transaction> found = transactions.find(null, null, null, DateRange.ALL, null, "amazon", 100, 0);
        // The two AMZN rows only by name; AMAZON.COM and AMAZON MKTP US by text as well.
        assertThat(found).extracting(Transaction::description).containsExactlyInAnyOrder(
                "AMZN MKTP US*2K4", "AMZN MKTP US*9QX", "AMAZON.COM*AB123", "AMAZON MKTP US 5521");
        assertThat(transactions.count(null, null, null, DateRange.ALL, null, "amazon")).isEqualTo(4);

        // A term that's in no name still searches text only.
        assertThat(transactions.find(null, null, null, DateRange.ALL, null, "AMAZING", 100, 0))
                .extracting(Transaction::description).containsExactly("AMAZING THAI");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a recurring series shows its merchant's name, and keeps its key for overrides")
    void recurringShowsName() {
        for (String month : List.of("01", "02", "03", "04", "05")) {
            seed(debit("2026-" + month + "-05", "NETFLIX.COM 4417", 15.49));
        }
        service.rename(List.of("NETFLIX.COM"), "Netflix");

        List<RecurringSeries> series = (List<RecurringSeries>) insights.recurring(Account.DEFAULT_ID, null, null).get("recurring");
        RecurringSeries netflix = series.stream().filter(s -> s.merchant().equals("NETFLIX.COM")).findFirst().orElseThrow();
        assertThat(netflix.name()).isEqualTo("Netflix");
    }

    @Test
    @DisplayName("a double charge shows its merchant's name")
    void duplicatesShowName() {
        seed(debit("2026-03-02", "AMZN MKTP US*2K4", 30), debit("2026-03-03", "AMZN MKTP US*7ZZ", 30));
        service.rename(List.of("AMZN MKTP US"), "Amazon");

        assertThat(duplicates.find(Account.DEFAULT_ID)).extracting(DuplicateChargeService.Group::merchant).containsExactly("Amazon");
    }

    @Test
    @DisplayName("lists every merchant with its count and commonest description, and names kept after their transactions")
    void listsMerchants() {
        seedAmazon();
        names.setAll(List.of("GONE MERCHANT"), "Gone");

        assertThat(merchant("AMZN MKTP US")).isEqualTo(new Merchant("AMZN MKTP US", null, 2, "AMZN MKTP US*2K4", "2026-03-09"));
        assertThat(merchant("GONE MERCHANT")).isEqualTo(new Merchant("GONE MERCHANT", "Gone", 0, null, null));
        assertThat(service.list().merchants().get(0).key()).isEqualTo("AMZN MKTP US");   // most transactions first
    }

    @Test
    @DisplayName("suggests look-alikes sharing a distinctive first word; not generic prefixes, not once merged or turned down")
    void suggestsLookAlikes() {
        seedAmazon();
        seed(debit("2026-03-01", "PAYPAL STEAMGAMES", 9), debit("2026-03-01", "PAYPAL SPOTIFY", 9),
                debit("2026-03-01", "GAP 11", 9), debit("2026-03-01", "GAP KIDS", 9));

        // AMZN MKTP US starts differently, so it isn't part of it; PAYPAL starts all sorts of
        // merchants; GAP is under four letters.
        assertThat(service.list().suggestions()).containsExactly(
                new Suggestion("AMAZON", List.of("AMAZON MKTP US", "AMAZON.COM"), "Amazon", 2));

        // Naming one of them suggests that name for the rest.
        service.rename(List.of("AMAZON.COM"), "Amazon (shop)");
        assertThat(service.list().suggestions()).extracting(Suggestion::name).containsExactly("Amazon (shop)");

        service.rename(List.of("AMAZON MKTP US"), "Amazon (shop)");
        assertThat(service.list().suggestions()).isEmpty();

        service.clear(List.of("AMAZON MKTP US"));
        service.dismissSuggestion("AMAZON");
        assertThat(service.list().suggestions()).isEmpty();
    }

    @Test
    @DisplayName("a suggested name is every word the spellings start with, not just the first")
    void suggestedNameUsesSharedWords() {
        assertThat(MerchantNameService.sharedLeadingWords(List.of("BLUE BOTTLE COFFEE", "BLUE BOTTLE COFFEE - POS PURCHASE"), "BLUE"))
                .isEqualTo("Blue Bottle Coffee");
        assertThat(MerchantNameService.sharedLeadingWords(List.of("TRADER JOE'S", "TRADER JOE'S MARKET"), "TRADER"))
                .isEqualTo("Trader Joe's");
        // No whole word in common -- "AMAZON.COM" isn't "AMAZON" -- so the stem that grouped them.
        assertThat(MerchantNameService.sharedLeadingWords(List.of("AMAZON.COM", "AMAZON MKTP US"), "AMAZON")).isEqualTo("Amazon");
    }

    @Test
    @DisplayName("a name differing only in case from one in use joins it rather than making a second merchant")
    void reusesExistingCasing() {
        service.rename(List.of("AMAZON.COM"), "Amazon");
        assertThat(service.rename(List.of("AMZN MKTP US"), "amazon")).isEqualTo("Amazon");
        // Renaming every holder of a name can still fix its case.
        assertThat(service.rename(List.of("AMAZON.COM", "AMZN MKTP US"), "AMAZON")).isEqualTo("AMAZON");
        assertThat(names.load().byKey()).containsEntry("AMAZON.COM", "AMAZON").containsEntry("AMZN MKTP US", "AMAZON");
    }

    @Test
    @DisplayName("names and turned-down suggestions are backed up; a file from before them restores none; reset keeps them")
    void backupAndReset() {
        service.rename(List.of("AMAZON.COM", "AMZN MKTP US"), "Amazon");
        service.dismissSuggestion("ZARA");

        BackupData exported = backups.export();
        names.deleteAll();
        names.restoreDismissedStems(List.of());
        backups.restore(exported);
        assertThat(names.findAll()).containsExactly(
                new MerchantNameRepository.MerchantName("AMAZON.COM", "Amazon"),
                new MerchantNameRepository.MerchantName("AMZN MKTP US", "Amazon"));
        assertThat(names.dismissedStems()).containsExactly("ZARA");

        transactionsApi.reset();
        assertThat(names.findAll()).hasSize(2);

        BackupData older = new BackupData(exported.version(), exported.exportedAt(), exported.accounts(), exported.categories(),
                exported.transactions(), exported.merchantCategories(), exported.budgets(), exported.recurringOverrides(),
                exported.goals(), exported.goalContributions(), exported.tags(), exported.transactionTags(),
                exported.accountBalances(), exported.filterPresets(), exported.transactionReceipts(),
                exported.netWorthTarget(), exported.keywordRules(), exported.transferPairs(),
                exported.duplicateDismissals(), null, null);
        backups.restore(older);
        assertThat(names.findAll()).isEmpty();
        assertThat(names.dismissedStems()).isEmpty();
    }

    @Test
    @DisplayName("the API rejects a blank or overlong name and an empty key list")
    void validates() {
        assertThat(api.rename(Map.of("keys", List.of("AMAZON.COM"), "name", "  ")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.rename(Map.of("keys", List.of("AMAZON.COM"), "name", "x".repeat(81))).getStatusCode().value()).isEqualTo(400);
        assertThat(api.rename(Map.of("keys", List.of(), "name", "Amazon")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.clear(Map.of("keys", List.of(" "))).getStatusCode().value()).isEqualTo(400);
        assertThat(api.dismiss(Map.of()).getStatusCode().value()).isEqualTo(400);

        assertThat(api.rename(Map.of("keys", List.of("AMAZON.COM"), "name", " Amazon ")).getStatusCode().value()).isEqualTo(200);
        assertThat(names.find("AMAZON.COM")).contains("Amazon");
    }
}
