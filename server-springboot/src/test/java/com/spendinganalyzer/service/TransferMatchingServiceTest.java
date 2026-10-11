package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.controller.TransferController;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.TransferSuggestion;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.model.TransferPair;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.repository.TransferPairRepository;
import com.spendinganalyzer.service.TransferMatchingService.PairRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransferMatchingServiceTest {

    @Autowired
    private TransferMatchingService service;

    @Autowired
    private TransferController controller;

    @Autowired
    private TransactionController transactionController;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TransferPairRepository pairs;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private StatsService stats;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final long checking = Account.DEFAULT_ID;
    private long savings;
    private long euro;

    @BeforeEach
    void setUp() {
        savings = accounts.create("Savings", "savings", "USD").id();
        euro = accounts.create("Euro account", "checking", "EUR").id();
    }

    private long add(long accountId, String date, String description, double amount, String type, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, type, category)), "transfer-test", accountId);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private Transaction get(long id) {
        return transactions.findById(id).orElseThrow();
    }

    private List<TransferSuggestion> suggestions() {
        return service.suggest(100).suggestions();
    }

    @Test
    @DisplayName("pairs a debit with the same amount credited to another account a few days later")
    void suggestsMatchingPair() {
        long out = add(checking, "2026-05-01", "ONLINE TRANSFER TO SAV", 500, "debit", null);
        long in = add(savings, "2026-05-03", "TRANSFER FROM CHK", 500, "credit", "Income");

        assertThat(suggestions()).singleElement().satisfies(s -> {
            assertThat(s.debit().id()).isEqualTo(out);
            assertThat(s.credit().id()).isEqualTo(in);
            assertThat(s.daysApart()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("ignores the same account, a different amount or currency, too many days apart, and split rows")
    void ignoresNearMisses() {
        add(checking, "2026-05-01", "A", 100, "debit", null);
        add(checking, "2026-05-01", "same account", 100, "credit", null);
        add(savings, "2026-05-01", "off by a cent", 100.01, "credit", null);
        add(euro, "2026-05-01", "other currency", 100, "credit", null);
        add(savings, "2026-05-05", "four days later", 100, "credit", null);

        long split = add(checking, "2026-06-01", "B", 80, "debit", null);
        transactions.updateSplit(split, 40.0, "half is Sam's");
        add(savings, "2026-06-01", "matches the split row", 80, "credit", null);

        assertThat(suggestions()).isEmpty();
    }

    @Test
    @DisplayName("each transaction pairs once, with the closest-dated partner")
    void closestPartnerWinsAndEachRowIsUsedOnce() {
        long d1 = add(checking, "2026-05-01", "D1", 200, "debit", null);
        long d2 = add(checking, "2026-05-02", "D2", 200, "debit", null);
        long far = add(savings, "2026-05-04", "C far", 200, "credit", null);
        long near = add(savings, "2026-05-02", "C near", 200, "credit", null);

        // d2 and "near" are the same day, so they pair first; d1 takes what's left.
        assertThat(suggestions())
                .extracting(s -> s.debit().id() + "->" + s.credit().id())
                .containsExactly(d2 + "->" + near, d1 + "->" + far);
    }

    @Test
    @DisplayName("a pair already in transfer categories on both sides isn't suggested; one side is enough to suggest")
    void skipsPairsAlreadyBothTransfers() {
        add(checking, "2026-05-01", "both done", 300, "debit", "Transfer");
        add(savings, "2026-05-01", "both done", 300, "credit", "Transfer");
        long half = add(checking, "2026-05-10", "half done", 50, "debit", "Transfer");
        add(savings, "2026-05-10", "counted as income", 50, "credit", "Income");

        assertThat(suggestions()).singleElement().extracting(s -> s.debit().id()).isEqualTo(half);
    }

    @Test
    @DisplayName("confirming moves both sides to Transfer, takes them out of spend and income, and remembers what they were")
    void confirmMovesBothSides() {
        long out = add(checking, "2026-05-01", "TO SAVINGS", 500, "debit", "Shopping");
        long in = add(savings, "2026-05-02", "FROM CHECKING", 500, "credit", "Income");
        assertThat(stats.computeIncomeTotal(null, DateRange.ALL)).isEqualTo(500);

        var result = service.confirm(List.of(new PairRef(out, in)));

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(get(out).category()).isEqualTo("Transfer");
        assertThat(get(out).categorySource()).isEqualTo("user");
        assertThat(get(in).category()).isEqualTo("Transfer");
        assertThat(stats.computeCategoryTotals(null, DateRange.ALL)).isEmpty();
        assertThat(stats.computeIncomeTotal(null, DateRange.ALL)).isZero();
        assertThat(suggestions()).isEmpty();
        assertThat(pairs.findAll()).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(TransferPair.CONFIRMED);
            assertThat(p.debitPrevCategory()).isEqualTo("Shopping");
            assertThat(p.debitPrevSource()).isEqualTo("import");
            assertThat(p.creditPrevCategory()).isEqualTo("Income");
        });
    }

    @Test
    @DisplayName("a side already in a more specific transfer category keeps it")
    void confirmKeepsExistingTransferCategory() {
        categories.create("Savings transfer", false, true);
        long out = add(checking, "2026-05-01", "TO SAVINGS", 500, "debit", "Savings transfer");
        long in = add(savings, "2026-05-01", "FROM CHECKING", 500, "credit", null);

        service.confirm(List.of(new PairRef(out, in)));

        assertThat(get(out).category()).isEqualTo("Savings transfer");
        assertThat(get(in).category()).isEqualTo("Transfer");
    }

    @Test
    @DisplayName("a pair that doesn't qualify is skipped with a reason, without failing the rest")
    void confirmSkipsInvalidPairs() {
        long out = add(checking, "2026-05-01", "A", 100, "debit", null);
        long in = add(savings, "2026-05-01", "B", 100, "credit", null);
        long other = add(savings, "2026-05-01", "C", 120, "credit", null);
        long again = add(savings, "2026-05-02", "D", 100, "credit", null);

        var result = service.confirm(List.of(
                new PairRef(out, other),     // amounts differ
                new PairRef(out, in),        // fine
                new PairRef(out, again),     // out is now taken
                new PairRef(in, out)));      // backwards

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.skipped()).extracting(TransferMatchingService.Skipped::reason).containsExactly(
                "The amounts differ.",
                "One of the transactions is already part of a transfer.",
                "A transfer is a debit paired with a credit.");
    }

    @Test
    @DisplayName("dismissing hides the suggestion without touching either transaction; undoing brings it back")
    void dismissAndUndo() {
        long out = add(checking, "2026-05-01", "PAID ALEX", 40, "debit", "Dining & Coffee");
        long in = add(savings, "2026-05-01", "REFUND", 40, "credit", null);

        assertThat(service.dismiss(List.of(new PairRef(out, in)))).isEqualTo(1);
        assertThat(suggestions()).isEmpty();
        assertThat(get(out).category()).isEqualTo("Dining & Coffee");

        long pairId = pairs.findAll().get(0).id();
        assertThat(service.undo(pairId)).contains(0);
        assertThat(suggestions()).hasSize(1);
    }

    @Test
    @DisplayName("undoing a confirmation restores both categories, except a side recategorized since")
    void undoRestoresCategories() {
        long out = add(checking, "2026-05-01", "TO SAVINGS", 500, "debit", "Shopping");
        long in = add(savings, "2026-05-02", "FROM CHECKING", 500, "credit", null);
        service.confirm(List.of(new PairRef(out, in)));
        transactions.updateCategory(out, "Rent/Mortgage", "user");

        long pairId = pairs.findAll().get(0).id();
        assertThat(service.undo(pairId)).contains(1);

        assertThat(get(out).category()).isEqualTo("Rent/Mortgage");
        assertThat(get(in).category()).isNull();
        assertThat(get(in).categorySource()).isNull();
        assertThat(pairs.findAll()).isEmpty();
        assertThat(service.undo(pairId)).isEmpty();
    }

    @Test
    @DisplayName("undo follows a renamed category")
    void undoFollowsRenamedCategory() {
        long out = add(checking, "2026-05-01", "TO SAVINGS", 500, "debit", "Shopping");
        long in = add(savings, "2026-05-02", "FROM CHECKING", 500, "credit", null);
        service.confirm(List.of(new PairRef(out, in)));
        Category shopping = categories.findAll().stream().filter(c -> c.name().equals("Shopping")).findFirst().orElseThrow();
        categories.rename(shopping.id(), "Shopping", "Stuff");

        service.undo(pairs.findAll().get(0).id());

        assertThat(get(out).category()).isEqualTo("Stuff");
    }

    @Test
    @DisplayName("with no transfer category left, confirming is refused with a 409")
    void noTransferCategory() {
        long out = add(checking, "2026-05-01", "A", 100, "debit", null);
        long in = add(savings, "2026-05-01", "B", 100, "credit", null);
        Category transfer = categories.findAll().stream().filter(Category::isTransfer).findFirst().orElseThrow();
        categories.updateFlags(transfer.id(), null, false);

        assertThat(service.transferCategory()).isEmpty();
        assertThatThrownBy(() -> service.confirm(List.of(new PairRef(out, in)))).isInstanceOf(IllegalStateException.class);
        var response = controller.confirm(Map.of("pairs", List.of(Map.of("debit_id", out, "credit_id", in))));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("deleting a transaction drops the pairs it was part of")
    void deletingTransactionDropsPair() {
        long out = add(checking, "2026-05-01", "A", 100, "debit", null);
        long in = add(savings, "2026-05-01", "B", 100, "credit", null);
        service.confirm(List.of(new PairRef(out, in)));

        transactionController.delete(out);

        assertThat(pairs.findAll()).isEmpty();
        assertThat(get(in).category()).isEqualTo("Transfer");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the controller lists confirmed and dismissed pairs separately, newest transfer first, and rejects an empty request")
    void controllerListsAndValidates() {
        long out = add(checking, "2026-05-01", "A", 100, "debit", null);
        long in = add(savings, "2026-05-01", "B", 100, "credit", null);
        long out2 = add(checking, "2026-05-08", "C", 60, "debit", null);
        long in2 = add(savings, "2026-05-08", "D", 60, "credit", null);

        controller.confirm(Map.of("pairs", List.of(Map.of("debit_id", out, "credit_id", in))));
        controller.dismiss(Map.of("pairs", List.of(Map.of("debit_id", out2, "credit_id", in2))));

        long older = add(checking, "2026-04-01", "E", 75, "debit", null);
        long olderIn = add(savings, "2026-04-01", "F", 75, "credit", null);
        // Older transfer recorded later: the list still goes by when the money moved.
        service.confirm(List.of(new PairRef(older, olderIn)));

        Map<String, Object> listed = controller.list();
        assertThat((List<com.spendinganalyzer.dto.TransferPairView>) listed.get("confirmed"))
                .extracting(v -> v.debit().id()).containsExactly(out, older);
        assertThat((List<?>) listed.get("dismissed")).hasSize(1);
        assertThat(controller.confirm(Map.of("pairs", List.of())).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.suggestions(0).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.undo(99999).getStatusCode().value()).isEqualTo(404);
    }
}
