package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MoneyFlow;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MoneyFlowServiceTest {

    @Autowired
    private MoneyFlowService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AccountRepository accounts;

    private void add(List<ParsedTransaction> rows) {
        transactions.insertBatch(rows, "flow-test", Account.DEFAULT_ID);
    }

    private long categoryId(String name) {
        return categories.findAll().stream().filter(c -> c.name().equals(name)).findFirst().map(Category::id).orElseThrow();
    }

    @Test
    @DisplayName("income by category on one side, spend by category with its group on the other, the rest saved")
    void flowsIncomeToSpendingAndSavings() {
        long paycheck = categories.create("Salary", true, false).id();
        categories.updateGroup(categoryId("Groceries"), "Essentials");
        add(List.of(
                new ParsedTransaction("2026-05-01", "PAYROLL", 3000, "credit", "Salary"),
                new ParsedTransaction("2026-05-03", "SIDE GIG", 500, "credit", "Income"),
                new ParsedTransaction("2026-05-04", "GROCER", 400, "debit", "Groceries"),
                new ParsedTransaction("2026-05-05", "CINEMA", 100.25, "debit", "Entertainment"),
                new ParsedTransaction("2026-05-06", "MYSTERY", 50, "debit", null),
                new ParsedTransaction("2026-05-07", "TO SAVINGS", 1000, "debit", "Transfer"),
                new ParsedTransaction("2026-05-07", "REFUND", 20, "credit", null)));

        MoneyFlow flow = service.flow(null, DateRange.ALL);

        assertThat(paycheck).isPositive();
        assertThat(flow.applicable()).isTrue();
        assertThat(flow.currency()).isEqualTo("USD");
        assertThat(flow.income()).containsExactly(
                new MoneyFlow.Source("Salary", 3000), new MoneyFlow.Source("Income", 500));
        assertThat(flow.spending()).containsExactly(
                new MoneyFlow.Sink("Groceries", "Essentials", 400),
                new MoneyFlow.Sink("Entertainment", null, 100.25),
                new MoneyFlow.Sink("Uncategorized", null, 50));
        assertThat(flow.totalIncome()).isEqualTo(3500);
        assertThat(flow.totalSpending()).isEqualTo(550.25);
        assertThat(flow.saved()).isEqualTo(2949.75);
        assertThat(flow.shortfall()).isZero();
    }

    @Test
    @DisplayName("spending more than came in shows a shortfall instead of savings, and honours the range")
    void shortfallWithinRange() {
        add(List.of(
                new ParsedTransaction("2026-04-01", "PAYROLL", 1000, "credit", "Income"),
                new ParsedTransaction("2026-05-01", "PAYROLL", 1000, "credit", "Income"),
                new ParsedTransaction("2026-05-10", "RENT", 1500, "debit", "Rent/Mortgage")));

        MoneyFlow flow = service.flow(null, DateRange.of("2026-05-01", "2026-05-31"));

        assertThat(flow.totalIncome()).isEqualTo(1000);
        assertThat(flow.saved()).isZero();
        assertThat(flow.shortfall()).isEqualTo(500);
    }

    @Test
    @DisplayName("refuses to add up accounts in different currencies")
    void notApplicableAcrossCurrencies() {
        accounts.create("Euro", "checking", "EUR");

        assertThat(service.flow(null, DateRange.ALL).applicable()).isFalse();
    }
}
