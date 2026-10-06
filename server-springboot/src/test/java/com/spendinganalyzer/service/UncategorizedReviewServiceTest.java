package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.UncategorizedMerchant;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The review queue groups what categorization couldn't place, and one answer per group sticks. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UncategorizedReviewServiceTest {

    @Autowired
    private UncategorizedReviewService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionController transactionController;

    @Autowired
    private MerchantCategoryRepository memory;

    private void seed(long accountId, ParsedTransaction... rows) {
        transactions.insertBatch(List.of(rows), "review-test", accountId);
    }

    private static ParsedTransaction row(String date, String description, double amount, String type) {
        return new ParsedTransaction(date, description, amount, type, null);
    }

    @Test
    @DisplayName("groups uncategorized transactions by merchant, across branches, most transactions first")
    void groupsByMerchant() {
        seed(1L,
                row("2026-05-01", "ZQX HOLDINGS #4471", 42, "debit"),
                row("2026-06-01", "ZQX HOLDINGS #8812", 42, "debit"),
                row("2026-07-01", "ZQX HOLDINGS #4471", 50, "debit"),
                row("2026-06-15", "LOCAL FARM STAND", 18.5, "debit"),
                new ParsedTransaction("2026-06-20", "ALREADY SET", 9, "debit", "Groceries"));

        List<UncategorizedMerchant> groups = service.merchants(null);

        assertThat(groups).extracting(UncategorizedMerchant::merchant).containsExactly("ZQX HOLDINGS", "LOCAL FARM STAND");
        UncategorizedMerchant zqx = groups.get(0);
        assertThat(zqx.count()).isEqualTo(3);
        assertThat(zqx.total()).isEqualTo(134.0);
        assertThat(zqx.debits()).isEqualTo(3);
        assertThat(zqx.firstDate()).isEqualTo("2026-05-01");
        assertThat(zqx.lastDate()).isEqualTo("2026-07-01");
        // Distinct raw descriptions, so the merchant is recognisable from what the bank printed.
        assertThat(zqx.examples()).containsExactly("ZQX HOLDINGS #4471", "ZQX HOLDINGS #8812");
        assertThat(zqx.transactionIds()).hasSize(3);
    }

    @Test
    @DisplayName("the same merchant in two currencies is two groups, never one total of unlike amounts")
    void separatesCurrencies() {
        long euro = accounts.create("Euro card", "credit_card", "EUR").id();
        seed(1L, row("2026-05-01", "ZQX HOLDINGS", 42, "debit"));
        seed(euro, row("2026-05-02", "ZQX HOLDINGS", 30, "debit"));

        assertThat(service.merchants(null))
                .extracting(UncategorizedMerchant::currency, UncategorizedMerchant::total)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("USD", 42.0),
                        org.assertj.core.groups.Tuple.tuple("EUR", 30.0));
        assertThat(service.merchants(euro)).singleElement()
                .extracting(UncategorizedMerchant::currency).isEqualTo("EUR");
    }

    @Test
    @DisplayName("answering a group through bulk-category clears it and teaches merchant memory, so the next import knows it")
    void answeringAGroupSticks() {
        seed(1L,
                row("2026-05-01", "ZQX HOLDINGS #4471", 42, "debit"),
                row("2026-06-01", "ZQX HOLDINGS #8812", 42, "debit"));
        UncategorizedMerchant group = service.merchants(null).get(0);

        transactionController.bulkCategory(Map.of("ids", group.transactionIds(), "category", "Shopping"));

        assertThat(service.merchants(null)).isEmpty();
        assertThat(memory.findByKey("ZQX HOLDINGS")).singleElement().satisfies(m -> {
            assertThat(m.category()).isEqualTo("Shopping");
            assertThat(m.source()).isEqualTo(MerchantCategory.SOURCE_USER);
        });
        List<Transaction> categorized = transactions.find("Shopping", null, 1L, DateRange.ALL, 10, 0);
        assertThat(categorized).hasSize(2).allSatisfy(t -> assertThat(t.categorySource()).isEqualTo("user"));
    }

    @Test
    @DisplayName("nothing uncategorized means an empty queue")
    void emptyQueue() {
        assertThat(service.merchants(null)).isEmpty();
    }
}
