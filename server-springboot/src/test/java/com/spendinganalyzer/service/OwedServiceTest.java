package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.OwedController;
import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.OwedSplit;
import com.spendinganalyzer.dto.TransactionWithTags;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.SplitRepaymentRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.RefundMatchingService.PairRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who owes you: split charges, what's still to come back, and repayments recorded against them. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OwedServiceTest {

    @Autowired private OwedService owed;
    @Autowired private OwedController api;
    @Autowired private SplitRepaymentRepository repayments;
    @Autowired private TransactionRepository transactions;
    @Autowired private TransactionController transactionsApi;
    @Autowired private RefundMatchingService refunds;
    @Autowired private StatsService stats;
    @Autowired private BackupService backups;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private long add(String date, String description, double amount, String type, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, type, category)),
                "owed-test", Account.DEFAULT_ID);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    /** A $90 dinner of which $30 is yours: $60 owed by Alex and Sam. */
    private long dinner() {
        long id = add("2026-05-01", "TRATTORIA ROMA", 90, "debit", "Dining & Coffee");
        transactions.updateSplit(id, 30.0, "Split 3 ways with Alex and Sam");
        return id;
    }

    private OwedSplit only() {
        List<OwedSplit> open = owed.list(Account.DEFAULT_ID).splits();
        assertThat(open).hasSize(1);
        return open.get(0);
    }

    private double income() {
        return stats.computeIncomeTotal(Account.DEFAULT_ID, DateRange.ALL);
    }

    @Test
    @DisplayName("the people a split note names are its words, less the ones that say what the split is")
    void namesInANote() {
        assertThat(OwedService.namesIn("Sam owes half")).containsExactly("sam");
        assertThat(OwedService.namesIn("Split 3 ways with Alex and Sam")).containsExactlyInAnyOrder("alex", "sam");
        assertThat(OwedService.namesIn(null)).isEmpty();
    }

    @Test
    @DisplayName("a split charge with money still to come back is listed with what's owed; others aren't")
    void listsOpenSplits() {
        long id = dinner();
        add("2026-05-02", "GROCER", 40, "debit", "Groceries");                 // not split
        long allMine = add("2026-05-03", "CINEMA", 24, "debit", "Entertainment");
        transactions.updateSplit(allMine, 24.0, "mine after all");              // nothing owed

        OwedSplit s = only();
        assertThat(s.transaction().id()).isEqualTo(id);
        assertThat(s.owed()).isEqualTo(60.0);
        assertThat(s.repaid()).isZero();
        assertThat(s.outstanding()).isEqualTo(60.0);
        assertThat(owed.list(Account.DEFAULT_ID).totals()).singleElement()
                .satisfies(t -> {
                    assertThat(t.outstanding()).isEqualTo(60.0);
                    assertThat(t.splits()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("credits from someone the note names, or for exactly what's outstanding, are suggested -- named first")
    void suggestsRepayments() {
        dinner();
        long sam = add("2026-05-04", "ZELLE FROM SAM K", 30, "credit", "Income");
        long venmo = add("2026-05-20", "VENMO CASHOUT", 60, "credit", "Income");
        add("2026-05-05", "PAYROLL", 25, "credit", "Income");                  // neither
        add("2026-04-20", "ZELLE FROM ALEX", 30, "credit", "Income");          // before the dinner
        add("2026-05-06", "ZELLE FROM ALEX", 75, "credit", "Income");          // more than is owed
        add("2026-05-07", "ZELLE FROM ALEX", 30, "credit", "Transfer");        // a transfer
        add("2026-09-30", "ZELLE FROM ALEX", 30, "credit", "Income");          // 152 days on

        assertThat(only().suggestions()).extracting(Transaction::id).containsExactly(sam, venmo);
    }

    @Test
    @DisplayName("linking a credit records its amount and date, and it stops counting as income")
    void linkingACredit() {
        long id = dinner();
        long sam = add("2026-05-04", "ZELLE FROM SAM", 30, "credit", "Income");
        add("2026-05-30", "PAYROLL", 2000, "credit", "Income");
        assertThat(income()).isEqualTo(2030.0);

        owed.record(id, null, sam, null);

        OwedSplit s = only();
        assertThat(s.repaid()).isEqualTo(30.0);
        assertThat(s.outstanding()).isEqualTo(30.0);
        assertThat(s.repayments()).singleElement().satisfies(r -> {
            assertThat(r.repaidOn()).isEqualTo("2026-05-04");
            assertThat(r.credit().id()).isEqualTo(sam);
        });
        assertThat(s.suggestions()).isEmpty();
        assertThat(income()).isEqualTo(2000.0);
        assertThat(transactions.findIncomeTransactions(Account.DEFAULT_ID, DateRange.ALL)).extracting("description")
                .containsExactly("PAYROLL");
    }

    @Test
    @DisplayName("a part paid by hand, then the rest: the split leaves the list once nothing is outstanding")
    void partThenSettle() {
        long id = dinner();
        owed.record(id, 10.0, null, "2026-05-10");
        assertThat(only().outstanding()).isEqualTo(50.0);

        owed.record(id, null, null, null);   // everything outstanding, today
        assertThat(owed.list(Account.DEFAULT_ID).splits()).isEmpty();
        assertThat(repayments.findForTransactions(List.of(id))).extracting("repaidOn")
                .containsExactly("2026-05-10", LocalDate.now().toString());
    }

    @Test
    @DisplayName("what can't be recorded says why")
    void refusals() {
        long id = dinner();
        long groceries = add("2026-05-02", "GROCER", 40, "debit", "Groceries");
        long big = add("2026-05-04", "ZELLE FROM SAM", 75, "credit", "Income");
        long sam = add("2026-05-05", "ZELLE FROM SAM", 20, "credit", "Income");
        long refund = add("2026-05-06", "GROCER REFUND", 40, "credit", "Income");

        assertThatThrownBy(() -> owed.record(groceries, null, null, null)).hasMessage("Only a split charge has money owed on it.");
        assertThatThrownBy(() -> owed.record(id, 61.0, null, null)).hasMessage("That's more than what's still owed on this charge.");
        assertThatThrownBy(() -> owed.record(id, 0.0, null, null)).hasMessage("amount must be greater than zero.");
        assertThatThrownBy(() -> owed.record(id, 5.0, null, "last week")).hasMessage("repaidOn must be a date like 2026-11-25.");
        assertThatThrownBy(() -> owed.record(id, null, big, null)).hasMessage("That credit is more than what's still owed on this charge.");
        assertThatThrownBy(() -> owed.record(id, null, groceries, null)).hasMessage("A repayment is money coming in.");
        assertThatThrownBy(() -> owed.record(999_999, null, null, null)).isInstanceOf(NoSuchElementException.class);

        owed.record(id, null, sam, null);
        assertThatThrownBy(() -> owed.record(id, null, sam, null)).hasMessage("That credit is already a repayment.");
        refunds.confirm(List.of(new PairRef(groceries, refund)));
        assertThatThrownBy(() -> owed.record(id, null, refund, null)).hasMessage("That credit is a refund of a purchase.");

        owed.record(id, null, null, null);
        assertThatThrownBy(() -> owed.record(id, 1.0, null, null)).hasMessage("Nothing is outstanding on this charge.");

        assertThat(api.record(id, Map.of("amount", "ten")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.record(999_999, Map.of()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("clearing the split brings a linked credit back as income; splitting again takes it out")
    void clearingTheSplit() {
        long id = dinner();
        long sam = add("2026-05-04", "ZELLE FROM SAM", 30, "credit", "Income");
        owed.record(id, null, sam, null);
        assertThat(income()).isZero();

        transactions.updateSplit(id, null, null);
        assertThat(income()).isEqualTo(30.0);
        assertThat(owed.list(Account.DEFAULT_ID).splits()).isEmpty();

        transactions.updateSplit(id, 30.0, "Alex and Sam");
        assertThat(income()).isZero();
        assertThat(only().outstanding()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("undo takes a repayment back; deleting the credit or the charge takes its repayments with it")
    void undoAndDelete() {
        long id = dinner();
        long sam = add("2026-05-04", "ZELLE FROM SAM", 30, "credit", "Income");
        long recorded = (long) ((Map<?, ?>) api.record(id, Map.of("repaymentId", sam)).getBody()).get("id");

        assertThat(api.undo(recorded).getStatusCode().value()).isEqualTo(200);
        assertThat(only().outstanding()).isEqualTo(60.0);
        assertThat(api.undo(recorded).getStatusCode().value()).isEqualTo(404);

        owed.record(id, null, sam, null);
        transactionsApi.delete(sam);
        assertThat(only().outstanding()).isEqualTo(60.0);

        owed.record(id, 15.0, null, null);
        transactionsApi.delete(id);
        assertThat(repayments.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the transactions list shows what's still owed on a split and what a credit repaid")
    void listRows() {
        long id = dinner();
        long sam = add("2026-05-04", "ZELLE FROM SAM", 30, "credit", "Income");
        long groceries = add("2026-05-02", "GROCER", 40, "debit", "Groceries");
        owed.record(id, null, sam, null);

        Map<Long, TransactionWithTags> rows = new HashMap<>();
        for (TransactionWithTags t : transactionsApi.list(null, null, null, null, null, null, null, 100, 0).transactions()) {
            rows.put(t.transaction().id(), t);
        }
        assertThat(rows.get(id).owedLeft()).isEqualTo(30.0);
        assertThat(rows.get(sam).repaymentFor()).isEqualTo("TRATTORIA ROMA");
        assertThat(rows.get(groceries).owedLeft()).isNull();
        assertThat(rows.get(groceries).repaymentFor()).isNull();

        owed.record(id, null, null, null);
        TransactionWithTags settled = transactionsApi.list(null, null, null, null, null, null, null, 100, 0).transactions()
                .stream().filter(t -> t.transaction().id() == id).findFirst().orElseThrow();
        assertThat(settled.owedLeft()).isZero();
    }

    @Test
    @DisplayName("repayments survive a backup and restore; a backup from before them restores with none")
    void backupRoundTrip() {
        long id = dinner();
        owed.record(id, 20.0, null, "2026-05-08");
        BackupData snapshot = backups.export();
        assertThat(snapshot.splitRepayments()).hasSize(1);

        repayments.deleteAll();
        assertThat(backups.restore(snapshot).splitRepayments()).isEqualTo(1);
        assertThat(only().outstanding()).isEqualTo(40.0);

        BackupData older = new BackupData(snapshot.version(), snapshot.exportedAt(), snapshot.accounts(), snapshot.categories(),
                snapshot.transactions(), snapshot.merchantCategories(), snapshot.budgets(), snapshot.recurringOverrides(),
                snapshot.goals(), snapshot.goalContributions(), snapshot.tags(), snapshot.transactionTags(),
                snapshot.accountBalances(), snapshot.filterPresets(), snapshot.transactionReceipts(),
                snapshot.netWorthTarget(), snapshot.keywordRules(), snapshot.transferPairs(),
                snapshot.duplicateDismissals(), snapshot.merchantNames(), snapshot.dismissedMerchantSuggestions(),
                snapshot.challenges(), snapshot.refundPairs(), null);
        backups.restore(older);
        assertThat(only().outstanding()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("the controller records with defaults and refuses nonsense")
    void controller() {
        long id = dinner();
        ResponseEntity<?> ok = api.record(id, null);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(owed.list(Account.DEFAULT_ID).splits()).isEmpty();
        assertThat(api.list(null).splits()).isEmpty();
    }
}
