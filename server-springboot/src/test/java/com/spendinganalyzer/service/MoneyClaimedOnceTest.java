package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
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

/**
 * Transfers, refunds and split repayments each take a credit out of income (and transfers and
 * refunds a debit out of spending). One transaction may be claimed by only one of them -- otherwise
 * the same money is hidden twice. (Review round five.)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MoneyClaimedOnceTest {

    @Autowired private TransferMatchingService transfers;
    @Autowired private RefundMatchingService refunds;
    @Autowired private OwedService owed;
    @Autowired private TransactionRepository transactions;
    @Autowired private TransactionController transactionsApi;
    @Autowired private AccountRepository accounts;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private long add(String date, String description, double amount, String type, String category, long accountId) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, type, category)),
                "claimed-once-test", accountId);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private long add(String date, String description, double amount, String type, String category) {
        return add(date, description, amount, type, category, Account.DEFAULT_ID);
    }

    /** A $90 dinner of which $30 is yours, and Sam's $60 back as a Zelle linked to it. */
    private long linkedRepayment() {
        long dinner = add("2026-05-01", "SAM'S BISTRO", 90, "debit", "Dining & Coffee");
        transactions.updateSplit(dinner, 30.0, "Sam owes the rest");
        long zelle = add("2026-05-03", "SAM'S BISTRO", 60, "credit", "Income");
        owed.record(dinner, null, zelle, null);
        return zelle;
    }

    @Test
    @DisplayName("a credit already linked as a repayment isn't offered or accepted as a refund")
    void repaymentIsNotARefund() {
        long purchase = add("2026-04-20", "SAM'S BISTRO", 60, "debit", "Dining & Coffee");
        long zelle = linkedRepayment();

        assertThat(refunds.suggest(100).suggestions()).noneMatch(s -> s.refund().id() == zelle);
        var result = refunds.confirm(List.of(new RefundMatchingService.PairRef(purchase, zelle)));
        assertThat(result.confirmed()).isZero();
        assertThat(result.skipped()).singleElement()
                .satisfies(s -> assertThat(s.reason()).isEqualTo("That credit is recorded as money paid back on a split."));
    }

    @Test
    @DisplayName("a confirmed refund, or a linked repayment, isn't offered or accepted as half of a transfer")
    void refundsAndRepaymentsAreNotTransfers() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        long jacket = add("2026-03-01", "OUTDOOR STORE", 250, "debit", "Shopping");
        long back = add("2026-03-20", "OUTDOOR STORE", 250, "credit", "Income");
        refunds.confirm(List.of(new RefundMatchingService.PairRef(jacket, back)));
        long sameDay = add("2026-03-20", "TO CHECKING", 250, "debit", "Other", savings);

        long zelle = linkedRepayment();
        long alsoSameDay = add("2026-05-03", "TO CHECKING", 60, "debit", "Other", savings);

        assertThat(transfers.suggest(100).suggestions()).isEmpty();
        var result = transfers.confirm(List.of(new TransferMatchingService.PairRef(sameDay, back),
                new TransferMatchingService.PairRef(alsoSameDay, zelle)));
        assertThat(result.confirmed()).isZero();
        assertThat(result.skipped()).extracting(TransferMatchingService.Skipped::reason).containsExactly(
                "That credit is a confirmed refund of a purchase.",
                "That credit is recorded as money paid back on a split.");
    }

    @Test
    @DisplayName("a purchase with a refund paired isn't half of a transfer either")
    void refundedPurchaseIsNotATransfer() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        long jacket = add("2026-03-01", "OUTDOOR STORE", 250, "debit", "Shopping");
        long back = add("2026-03-20", "OUTDOOR STORE REFUND", 100, "credit", "Income");
        refunds.confirm(List.of(new RefundMatchingService.PairRef(jacket, back)));
        long arrived = add("2026-03-02", "FROM CHECKING", 250, "credit", "Income", savings);

        assertThat(transfers.suggest(100).suggestions()).isEmpty();
        assertThat(transfers.confirm(List.of(new TransferMatchingService.PairRef(jacket, arrived))).skipped())
                .extracting(TransferMatchingService.Skipped::reason)
                .containsExactly("That debit has a refund paired with it.");
    }

    @Test
    @DisplayName("half of a confirmed transfer can't be recorded as money paid back on a split")
    void transferIsNotARepayment() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        long dinner = add("2026-05-01", "BISTRO", 90, "debit", "Dining & Coffee");
        transactions.updateSplit(dinner, 30.0, "Sam owes the rest");
        long out = add("2026-05-03", "TO CHECKING", 60, "debit", "Other", savings);
        long in = add("2026-05-03", "FROM SAVINGS", 60, "credit", "Other");
        assertThat(transfers.confirm(List.of(new TransferMatchingService.PairRef(out, in))).confirmed()).isEqualTo(1);

        assertThatThrownBy(() -> owed.record(dinner, null, in, null))
                .hasMessage("That credit is half of a transfer between your accounts.");
    }

    @Test
    @DisplayName("a purchase with a refund paired can't be split -- what's owed would ignore the refund")
    void refundedPurchaseCantBeSplit() {
        long dinner = add("2026-03-01", "TRATTORIA", 100, "debit", "Dining & Coffee");
        long back = add("2026-03-10", "TRATTORIA REFUND", 40, "credit", "Income");
        refunds.confirm(List.of(new RefundMatchingService.PairRef(dinner, back)));

        var response = transactionsApi.update(dinner, Map.of("split_share", 20, "split_note", "Sam owes half"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(transactions.findById(dinner).orElseThrow().splitShare()).isNull();
        assertThatThrownBy(() -> owed.record(dinner, null, null, null)).hasMessage("Only a split charge has money owed on it.");
    }
}
