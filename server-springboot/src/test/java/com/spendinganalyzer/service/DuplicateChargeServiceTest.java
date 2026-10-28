package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.DuplicateChargeController;
import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.DuplicateDismissalRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.DuplicateChargeService.Group;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DuplicateChargeServiceTest {

    @Autowired
    private DuplicateChargeService service;

    @Autowired
    private DuplicateChargeController controller;

    @Autowired
    private TransactionController transactionController;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private DuplicateDismissalRepository dismissals;

    @Autowired
    private BackupService backupService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final long checking = Account.DEFAULT_ID;

    private long add(long accountId, String date, String description, double amount, String type, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, type, category)), "dup-test", accountId);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private long debit(String date, String description, double amount) {
        return add(checking, date, description, amount, "debit", null);
    }

    private static List<Long> ids(Group g) {
        return g.transactions().stream().map(Transaction::id).toList();
    }

    @Test
    @DisplayName("the same amount at the same merchant a day apart is a likely double charge; store numbers don't matter")
    void findsPair() {
        long a = debit("2026-05-01", "ELECTRONICS HUB #0042", 249.99);
        long b = debit("2026-05-02", "ELECTRONICS HUB #0117", 249.99);

        assertThat(service.find(null)).singleElement().satisfies(g -> {
            assertThat(ids(g)).containsExactly(a, b);
            assertThat(g.amount()).isEqualTo(249.99);
            assertThat(g.accountId()).isEqualTo(checking);
        });
    }

    @Test
    @DisplayName("different accounts, amounts, more than two days apart, transfers and credits aren't double charges")
    void ignoresNearMisses() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        debit("2026-05-01", "GADGET STORE", 80);
        add(savings, "2026-05-01", "GADGET STORE", 80, "debit", null);          // other account
        debit("2026-06-01", "BOOK SHOP", 20);
        debit("2026-06-01", "BOOK SHOP", 20.01);                                // other amount
        debit("2026-07-01", "TICKETS LTD", 45);
        debit("2026-07-04", "TICKETS LTD", 45);                                 // three days on
        debit("2026-08-01", "MOVE TO SAVINGS", 100);
        add(checking, "2026-08-01", "MOVE TO SAVINGS", 100, "debit", "Transfer"); // a transfer
        add(checking, "2026-09-01", "REFUND DESK", 30, "credit", null);
        add(checking, "2026-09-01", "REFUND DESK", 30, "credit", null);         // money in

        assertThat(service.find(null)).isEmpty();
    }

    @Test
    @DisplayName("a merchant and amount seen on four or more days is a habit, so even a same-day pair isn't flagged")
    void habitsAreSkipped() {
        debit("2026-05-01", "CORNER COFFEE", 4.50);
        debit("2026-05-01", "CORNER COFFEE", 4.50);
        debit("2026-05-03", "CORNER COFFEE", 4.50);
        debit("2026-05-04", "CORNER COFFEE", 4.50);
        debit("2026-05-06", "CORNER COFFEE", 4.50);

        assertThat(service.find(null)).isEmpty();
    }

    @Test
    @DisplayName("charges chain into one group while each is within two days of the last")
    void chainsIntoOneGroup() {
        long a = debit("2026-05-01", "PARKING CO", 12);
        long b = debit("2026-05-03", "PARKING CO", 12);
        long c = debit("2026-05-04", "PARKING CO", 12);

        assertThat(service.find(null)).singleElement().extracting(DuplicateChargeServiceTest::ids)
                .isEqualTo(List.of(a, b, c));
    }

    @Test
    @DisplayName("'not a duplicate' hides that exact group; another matching charge makes a new group that's offered again")
    void dismissal() {
        long a = debit("2026-05-01", "CINEMA PLEX", 15);
        long b = debit("2026-05-01", "CINEMA PLEX", 15);

        assertThat(controller.dismiss(Map.of("transaction_ids", List.of(b, a))).getStatusCode().value()).isEqualTo(200);
        assertThat(service.find(null)).isEmpty();

        long c = debit("2026-05-02", "CINEMA PLEX", 15);
        assertThat(service.find(null)).singleElement().extracting(DuplicateChargeServiceTest::ids)
                .isEqualTo(List.of(a, b, c));
        assertThat(controller.dismiss(Map.of("transaction_ids", List.of(a))).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("filters by account, and lists the newest group first")
    void accountFilterAndOrder() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        debit("2026-03-01", "OLD SHOP", 9);
        debit("2026-03-01", "OLD SHOP", 9);
        debit("2026-06-01", "NEW SHOP", 9);
        debit("2026-06-02", "NEW SHOP", 9);
        add(savings, "2026-04-01", "BANK FEE", 5, "debit", null);
        add(savings, "2026-04-01", "BANK FEE", 5, "debit", null);

        assertThat(service.find(null)).extracting(Group::merchant).containsExactly("NEW SHOP", "BANK FEE", "OLD SHOP");
        assertThat(service.find(savings)).extracting(Group::merchant).containsExactly("BANK FEE");
    }

    @Test
    @DisplayName("dismissals survive a backup and restore, and reset clears them")
    void backupAndReset() {
        long a = debit("2026-05-01", "CINEMA PLEX", 15);
        long b = debit("2026-05-01", "CINEMA PLEX", 15);
        service.dismiss(List.of(a, b));

        backupService.restore(backupService.export());
        assertThat(dismissals.keys()).containsExactly(a + "," + b);
        assertThat(service.find(null)).isEmpty();

        transactionController.reset();
        assertThat(dismissals.keys()).isEmpty();
    }
}
