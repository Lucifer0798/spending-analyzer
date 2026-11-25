package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.RefundController;
import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.RefundSuggestion;
import com.spendinganalyzer.dto.TransactionWithTags;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.RefundPair;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.RefundPairRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.RefundMatchingService.PairRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Refunds paired with the purchases they reverse: what's suggested, and what confirming changes. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RefundMatchingTest {

    @Autowired private RefundMatchingService refunds;
    @Autowired private RefundPairRepository pairs;
    @Autowired private TransactionRepository transactions;
    @Autowired private AccountRepository accounts;
    @Autowired private MerchantNameService names;
    @Autowired private StatsService stats;
    @Autowired private DuplicateChargeService duplicates;
    @Autowired private BackupService backups;
    @Autowired private TransactionController transactionsApi;
    @Autowired private RefundController refundsApi;
    @Autowired private CsvExportService csv;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private UncategorizedReviewService uncategorized;

    private static final DateRange ALL = DateRange.ALL;

    private long add(String date, String description, double amount, String type, String category, long accountId) {
        return add(date, description, amount, type, category, accountId, "refund-test");
    }

    private long add(String date, String description, double amount, String type, String category, long accountId,
                     String batchId) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, type, category)),
                batchId, accountId);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private long buy(String date, String description, double amount) {
        return add(date, description, amount, "debit", "Shopping", Account.DEFAULT_ID);
    }

    private long credit(String date, String description, double amount) {
        return add(date, description, amount, "credit", "Income", Account.DEFAULT_ID);
    }

    private List<String> suggested() {
        return refunds.suggest(100).suggestions().stream()
                .map(s -> s.purchase().id() + "<-" + s.refund().id()).toList();
    }

    private static String pair(long purchase, long refund) {
        return purchase + "<-" + refund;
    }

    private double shopping() {
        return stats.computeCategoryTotals(Account.DEFAULT_ID, ALL).stream()
                .filter(c -> c.category().equals("Shopping")).mapToDouble(CategoryTotal::total).sum();
    }

    private int shoppingCount() {
        return stats.computeCategoryTotals(Account.DEFAULT_ID, ALL).stream()
                .filter(c -> c.category().equals("Shopping")).mapToInt(CategoryTotal::count).sum();
    }

    @Test
    @DisplayName("a credit's merchant is read with refund words set aside")
    void merchantOfARefund() {
        assertThat(RefundMatchingService.refundMerchantOf("REFUND - AMAZON.COM*ZZ771"))
                .isEqualTo(RefundMatchingService.merchantOf("AMAZON.COM*AB123"));
        assertThat(RefundMatchingService.refundMerchantOf("ELECTRONICS HUB #0117 RETURN"))
                .isEqualTo(RefundMatchingService.merchantOf("ELECTRONICS HUB #0042"));
        assertThat(RefundMatchingService.refundMerchantOf("CREDIT: SHOE PALACE"))
                .isEqualTo(RefundMatchingService.merchantOf("SHOE PALACE 2231"));
        assertThat(RefundMatchingService.refundMerchantOf("REFUND")).isEqualTo("REFUND");
        assertThat(RefundMatchingService.hasRefundWord("Amazon refund")).isTrue();
        assertThat(RefundMatchingService.hasRefundWord("CREDIT: SHOE PALACE")).isFalse();
        assertThat(RefundMatchingService.hasRefundWord("RETURNING CUSTOMER BONUS")).isFalse();
    }

    @Test
    @DisplayName("the whole amount back from the same merchant in the same account, within 120 days, is suggested")
    void suggestsWholeRefunds() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE #12", 250);
        long back = credit("2026-03-20", "OUTDOOR STORE #12", 250);
        long other = accounts.create("Card", "credit_card", "USD").id();
        long elsewhere = add("2026-03-10", "OUTDOOR STORE #12", 250, "credit", "Income", other);  // nearer, but another account
        credit("2026-02-20", "OUTDOOR STORE #12", 250);            // before the purchase
        credit("2026-08-01", "OUTDOOR STORE #12", 250);            // 153 days on
        credit("2026-03-22", "OUTDOOR STORE #12", 300);            // more than was spent
        credit("2026-03-23", "SALARY", 250);                       // another merchant
        buy("2026-01-05", "LATE SHOP", 40);
        credit("2026-06-01", "LATE SHOP", 40);                     // its only match, 147 days on

        RefundSuggestion s = refunds.suggest(100).suggestions().get(0);
        assertThat(suggested()).containsExactly(pair(jacket, back));
        assertThat(s.exactAmount()).isTrue();
        assertThat(s.daysAfter()).isEqualTo(19);
        assertThat(elsewhere).isNotEqualTo(back);   // the same credit in another account isn't offered
    }

    @Test
    @DisplayName("a partial refund is suggested only when its description says it's a refund")
    void partialNeedsARefundWord() {
        long order = buy("2026-04-01", "AMAZON.COM*AB123", 120);
        long partial = credit("2026-04-10", "AMAZON.COM REFUND", 40);
        credit("2026-04-11", "AMAZON.COM*ZZ9", 25);                // partial, no refund word

        assertThat(suggested()).containsExactly(pair(order, partial));
        assertThat(refunds.suggest(100).suggestions().get(0).exactAmount()).isFalse();
    }

    @Test
    @DisplayName("each credit refunds one purchase -- the nearest before it -- and a purchase only up to its amount")
    void oneRefundOnePurchase() {
        long first = buy("2026-05-01", "SHOE PALACE 2231", 80);
        long second = buy("2026-05-10", "SHOE PALACE 2231", 80);
        long refund = credit("2026-05-12", "SHOE PALACE RETURN", 80);
        assertThat(suggested()).containsExactly(pair(second, refund));

        // Two partial refunds of one $100 purchase: the second would take it past what was spent.
        long jacket = buy("2026-06-01", "COAT CO", 100);
        long a = credit("2026-06-05", "COAT CO REFUND", 60);
        long b = credit("2026-06-06", "COAT CO REFUND", 60);
        assertThat(suggested()).contains(pair(jacket, a)).doesNotContain(pair(jacket, b));
        assertThat(first).isLessThan(second);
    }

    @Test
    @DisplayName("merchants under one name match, however differently the purchase and refund are spelled")
    void namedMerchantsMatch() {
        long order = buy("2026-04-01", "AMAZON.COM*AB123", 60);
        long back = credit("2026-04-09", "AMZN MKTP REFUND", 60);
        assertThat(suggested()).isEmpty();

        names.rename(List.of("AMAZON.COM", "AMZN MKTP"), "Amazon");
        assertThat(suggested()).containsExactly(pair(order, back));
    }

    @Test
    @DisplayName("confirming takes the refund off spending and out of income; a full refund leaves spending entirely")
    void confirmingNets() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 250);
        long shoes = buy("2026-03-02", "SHOE PALACE", 100);
        long back = credit("2026-03-20", "OUTDOOR STORE", 250);
        long part = credit("2026-03-21", "SHOE PALACE REFUND", 30);
        credit("2026-03-25", "SALARY", 2000);

        assertThat(shopping()).isEqualTo(350.0);
        assertThat(shoppingCount()).isEqualTo(2);
        assertThat(stats.computeIncomeTotal(Account.DEFAULT_ID, ALL)).isEqualTo(2280.0);

        var result = refunds.confirm(List.of(new PairRef(jacket, back), new PairRef(shoes, part)));
        assertThat(result.confirmed()).isEqualTo(2);

        assertThat(shopping()).isEqualTo(70.0);
        assertThat(shoppingCount()).isEqualTo(1);                  // the jacket is gone, the shoes are $70
        assertThat(stats.computeMonthlyTotals(Account.DEFAULT_ID, ALL)).singleElement()
                .satisfies(m -> assertThat(m.total()).isEqualTo(70.0));
        assertThat(stats.computeIncomeTotal(Account.DEFAULT_ID, ALL)).isEqualTo(2000.0);
        assertThat(transactions.findIncomeTransactions(Account.DEFAULT_ID, ALL)).extracting("description")
                .containsExactly("SALARY");
        assertThat(transactions.findSpendingTransactions(Account.DEFAULT_ID, ALL)).extracting("description")
                .containsExactly("SHOE PALACE");
        assertThat(suggested()).isEmpty();
    }

    @Test
    @DisplayName("confirming re-checks each pair and skips one that no longer fits, with a reason")
    void confirmSkips() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 100);
        long a = credit("2026-03-05", "OUTDOOR STORE REFUND", 70);
        long b = credit("2026-03-06", "OUTDOOR STORE REFUND", 70);
        long early = credit("2026-02-01", "OUTDOOR STORE REFUND", 10);
        long salary = buy("2026-03-07", "SALARY ADVANCE", 50);

        var result = refunds.confirm(List.of(new PairRef(jacket, a), new PairRef(jacket, b), new PairRef(jacket, a),
                new PairRef(jacket, early), new PairRef(salary, jacket), new PairRef(jacket, 999_999)));
        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.skipped()).extracting(RefundMatchingService.Skipped::reason).containsExactly(
                "The refund is more than what's left of the purchase.",
                "That credit is already a refund of another purchase.",
                "The refund is dated before the purchase.",
                "A refund is a credit paired with a purchase.",
                "One of the transactions no longer exists.");
    }

    @Test
    @DisplayName("\"not a refund\" stops the suggestion; undoing either kind of pair puts things back")
    void dismissAndUndo() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 250);
        long back = credit("2026-03-20", "OUTDOOR STORE", 250);

        refunds.dismiss(List.of(new PairRef(jacket, back)));
        assertThat(suggested()).isEmpty();
        assertThat(shopping()).isEqualTo(250.0);
        long dismissed = pairs.findAll().get(0).id();
        assertThat(refunds.undo(dismissed)).isTrue();
        assertThat(suggested()).containsExactly(pair(jacket, back));

        refunds.confirm(List.of(new PairRef(jacket, back)));
        assertThat(shopping()).isZero();
        RefundPair confirmed = pairs.findAll().get(0);
        assertThat(refundsApi.undo(confirmed.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(shopping()).isEqualTo(250.0);
        assertThat(stats.computeIncomeTotal(Account.DEFAULT_ID, ALL)).isEqualTo(250.0);
        assertThat(refundsApi.undo(confirmed.id()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleting either side drops the pair, so the purchase counts in full again")
    void deletingDropsThePair() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 250);
        long back = credit("2026-03-20", "OUTDOOR STORE", 250);
        refunds.confirm(List.of(new PairRef(jacket, back)));

        transactionsApi.delete(back);

        assertThat(pairs.findAll()).isEmpty();
        assertThat(shopping()).isEqualTo(250.0);
    }

    @Test
    @DisplayName("the transactions list says what was refunded and what a refund was for; the CSV has a refunded column")
    void listAndCsv() {
        long shoes = buy("2026-03-02", "SHOE PALACE", 100);
        long part = credit("2026-03-21", "SHOE PALACE REFUND", 30);
        refunds.confirm(List.of(new PairRef(shoes, part)));

        Map<Long, TransactionWithTags> rows = new java.util.HashMap<>();
        for (TransactionWithTags t : transactionsApi.list(null, null, null, null, null, null, null, 100, 0).transactions()) {
            rows.put(t.transaction().id(), t);
        }
        assertThat(rows.get(shoes).refunded()).isEqualTo(30.0);
        assertThat(rows.get(shoes).refundFor()).isNull();
        assertThat(rows.get(part).refundFor()).isEqualTo("SHOE PALACE");
        assertThat(rows.get(part).refunded()).isNull();

        String file = new String(csv.transactions(transactions.findByIds(List.of(shoes, part)),
                pairs.refundedByPurchase(List.of(shoes, part))), StandardCharsets.UTF_8);
        assertThat(file.lines().findFirst().orElseThrow()).endsWith(",note,refunded");
        assertThat(file).contains("SHOE PALACE,Shopping,debit,100.0").containsPattern("SHOE PALACE,Shopping,debit.*,30\\.0\\r?\\n");
    }

    @Test
    @DisplayName("a double charge refunded in full is no longer a double charge")
    void refundedDoubleCharge() {
        long first = buy("2026-03-01", "GADGET SHOP", 49.99);
        buy("2026-03-02", "GADGET SHOP", 49.99);
        assertThat(duplicates.find(Account.DEFAULT_ID)).hasSize(1);

        long back = credit("2026-03-09", "GADGET SHOP REFUND", 49.99);
        assertThat(suggested()).hasSize(1);
        refunds.confirm(List.of(new PairRef(refunds.suggest(1).suggestions().get(0).purchase().id(), back)));

        assertThat(duplicates.find(Account.DEFAULT_ID)).isEmpty();
        assertThat(first).isPositive();
    }

    @Test
    @DisplayName("pairs survive a backup and restore; a backup from before refunds restores with none")
    void backupRoundTrip() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 250);
        long back = credit("2026-03-20", "OUTDOOR STORE", 250);
        refunds.confirm(List.of(new PairRef(jacket, back)));
        BackupData snapshot = backups.export();
        assertThat(snapshot.refundPairs()).hasSize(1);

        pairs.deleteAll();
        assertThat(backups.restore(snapshot).refundPairs()).isEqualTo(1);
        assertThat(shopping()).isZero();

        BackupData older = new BackupData(snapshot.version(), snapshot.exportedAt(), snapshot.accounts(), snapshot.categories(),
                snapshot.transactions(), snapshot.merchantCategories(), snapshot.budgets(), snapshot.recurringOverrides(),
                snapshot.goals(), snapshot.goalContributions(), snapshot.tags(), snapshot.transactionTags(),
                snapshot.accountBalances(), snapshot.filterPresets(), snapshot.transactionReceipts(),
                snapshot.netWorthTarget(), snapshot.keywordRules(), snapshot.transferPairs(),
                snapshot.duplicateDismissals(), snapshot.merchantNames(), snapshot.dismissedMerchantSuggestions(),
                snapshot.challenges(), null, null);
        backups.restore(older);
        assertThat(pairs.findAll()).isEmpty();
        assertThat(shopping()).isEqualTo(250.0);
    }

    @Test
    @DisplayName("an import summary asks only after refunds with a side from that upload")
    void suggestionsForOneUpload() {
        long jacket = add("2026-03-01", "OUTDOOR STORE", 250, "debit", "Shopping", Account.DEFAULT_ID, "march");
        long back = add("2026-03-20", "OUTDOOR STORE", 250, "credit", "Income", Account.DEFAULT_ID, "april");
        long shoes = add("2026-03-02", "SHOE PALACE", 100, "debit", "Shopping", Account.DEFAULT_ID, "march");
        long part = add("2026-03-21", "SHOE PALACE REFUND", 30, "credit", "Income", Account.DEFAULT_ID, "march");

        assertThat(refunds.suggest(100, "april").suggestions()).extracting(r -> pair(r.purchase().id(), r.refund().id()))
                .containsExactly(pair(jacket, back));
        assertThat(refunds.suggest(100, "march").total()).isEqualTo(2);
        assertThat(refunds.suggest(100, "may").total()).isZero();
        assertThat(refunds.suggest(100, null).total()).isEqualTo(2);
        assertThat(part).isGreaterThan(shoes);
    }

    @Test
    @DisplayName("a paired refund isn't asked about in the uncategorized review -- a category would change nothing")
    void pairedRefundNotUncategorized() {
        long jacket = buy("2026-03-01", "OUTDOOR STORE", 250);
        long back = add("2026-03-20", "OUTDOOR STORE REFUND", 250, "credit", null, Account.DEFAULT_ID);
        add("2026-03-22", "MYSTERY DEPOSIT", 40, "credit", null, Account.DEFAULT_ID);
        assertThat(uncategorized.merchants(Account.DEFAULT_ID)).extracting("merchant")
                .containsExactlyInAnyOrder("OUTDOOR STORE REFUND", "MYSTERY DEPOSIT");

        refunds.confirm(List.of(new PairRef(jacket, back)));

        assertThat(uncategorized.merchants(Account.DEFAULT_ID)).extracting("merchant").containsExactly("MYSTERY DEPOSIT");
    }
}
