package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.ReapplyRulesResult;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Re-deciding rule-set categories after rules change -- and leaving everything else alone. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReapplyRulesTest {

    @Autowired
    private CategorizationService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private KeywordRuleRepository keywordRules;

    @Autowired
    private MerchantCategoryRepository memory;

    /** Seeds one row per description and sets its category and source as if earlier runs had. */
    @BeforeEach
    void seed() {
        memory.deleteAll();
        transactions.insertBatch(List.of(
                debit("CITY VET CLINIC"),        // a built-in rule (CLINIC) made it Healthcare
                debit("ZQX HOLDINGS 4471"),      // a keyword rule since deleted made it Shopping
                debit("WHOLE FOODS MARKET #12"), // a built-in rule made it Groceries
                debit("SHELL OIL 57444"),        // a built-in rule made it Transportation
                debit("STARBUCKS STORE 4521"),   // the user set it
                debit("NETFLIX.COM")             // the language model set it, before the rules existed
        ), "reapply-test", 1L);
        set("CITY VET CLINIC", "Healthcare", "rule");
        set("ZQX HOLDINGS 4471", "Shopping", "rule");
        set("WHOLE FOODS MARKET #12", "Groceries", "rule");
        set("SHELL OIL 57444", "Transportation", "rule");
        set("STARBUCKS STORE 4521", "Groceries", "user");
        set("NETFLIX.COM", "Entertainment", "ai");

        // What changed since: a new keyword rule, and the user taught memory about Whole Foods.
        keywordRules.create("VET", "Personal Care");
        memory.saveRule("WHOLE FOODS MARKET", "Shopping", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);
    }

    private static ParsedTransaction debit(String description) {
        return new ParsedTransaction("2026-05-01", description, 20, "debit", null);
    }

    private Map<String, Transaction> byDescription() {
        return transactions.find(null, null, 1L, DateRange.ALL, 100, 0).stream()
                .collect(Collectors.toMap(Transaction::description, Function.identity()));
    }

    private void set(String description, String category, String source) {
        transactions.updateCategory(byDescription().get(description).id(), category, source);
    }

    @Test
    @DisplayName("a dry run reports every change, including ones going back to uncategorized, and writes nothing")
    void dryRunWritesNothing() {
        ReapplyRulesResult result = service.reapplyRules(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.checked()).isEqualTo(4);      // only the 'rule' rows
        assertThat(result.changed()).isEqualTo(2);      // the vet (keyword now), Whole Foods (memory now)
        assertThat(result.cleared()).isEqualTo(1);      // ZQX: its rule is gone
        assertThat(result.unchanged()).isEqualTo(1);    // Shell
        assertThat(result.examples())
                .extracting(ReapplyRulesResult.Change::description, ReapplyRulesResult.Change::from, ReapplyRulesResult.Change::to)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CITY VET CLINIC", "Healthcare", "Personal Care"),
                        org.assertj.core.groups.Tuple.tuple("ZQX HOLDINGS 4471", "Shopping", null),
                        org.assertj.core.groups.Tuple.tuple("WHOLE FOODS MARKET #12", "Groceries", "Shopping"));

        assertThat(byDescription().get("CITY VET CLINIC").category()).isEqualTo("Healthcare");
        assertThat(byDescription().get("ZQX HOLDINGS 4471").category()).isEqualTo("Shopping");
    }

    @Test
    @DisplayName("applying re-decides rule-set rows -- memory and keyword rules now win -- and never touches user or ai rows")
    void applyRedecidesOnlyRuleRows() {
        service.reapplyRules(false);
        Map<String, Transaction> after = byDescription();

        assertThat(after.get("CITY VET CLINIC")).satisfies(t -> {
            assertThat(t.category()).isEqualTo("Personal Care");
            assertThat(t.categorySource()).isEqualTo("rule");
        });
        assertThat(after.get("WHOLE FOODS MARKET #12")).satisfies(t -> {
            assertThat(t.category()).isEqualTo("Shopping");
            assertThat(t.categorySource()).isEqualTo("cache");   // memory answered, labelled as such
        });
        assertThat(after.get("ZQX HOLDINGS 4471")).satisfies(t -> {
            assertThat(t.category()).isNull();
            assertThat(t.categorySource()).isNull();
        });
        assertThat(after.get("SHELL OIL 57444").category()).isEqualTo("Transportation");
        // Untouchable: the user's choice and the model's old answer.
        assertThat(after.get("STARBUCKS STORE 4521").category()).isEqualTo("Groceries");
        assertThat(after.get("STARBUCKS STORE 4521").categorySource()).isEqualTo("user");
        assertThat(after.get("NETFLIX.COM").category()).isEqualTo("Entertainment");
        assertThat(after.get("NETFLIX.COM").categorySource()).isEqualTo("ai");
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void idempotent() {
        service.reapplyRules(false);

        ReapplyRulesResult second = service.reapplyRules(true);

        assertThat(second.changed()).isZero();
        assertThat(second.cleared()).isZero();
        assertThat(second.examples()).isEmpty();
    }
}
