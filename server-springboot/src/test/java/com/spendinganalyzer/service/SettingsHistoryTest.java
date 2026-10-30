package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.BudgetController;
import com.spendinganalyzer.controller.CategoryController;
import com.spendinganalyzer.controller.KeywordRuleController;
import com.spendinganalyzer.controller.MerchantController;
import com.spendinganalyzer.controller.MerchantNameController;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.KeywordRule;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.MerchantNameRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChangeLogService.Entry;
import com.spendinganalyzer.service.ChangeLogService.UndoResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Manage-page changes -- categories, budgets, keyword rules, merchant memory and names -- in History, with Undo. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SettingsHistoryTest {

    @Autowired private ChangeLogService changeLog;
    @Autowired private CategoryController categoriesApi;
    @Autowired private BudgetController budgetsApi;
    @Autowired private KeywordRuleController rulesApi;
    @Autowired private MerchantController memoryApi;
    @Autowired private MerchantNameController namesApi;
    @Autowired private CategoryRepository categories;
    @Autowired private BudgetRepository budgets;
    @Autowired private KeywordRuleRepository rules;
    @Autowired private MerchantCategoryRepository memory;
    @Autowired private MerchantNameRepository names;
    @Autowired private TransactionRepository transactions;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private Entry latest() {
        return changeLog.recent(1).get(0);
    }

    private UndoResult undoLatest() {
        return changeLog.undo(latest().id()).orElseThrow();
    }

    private int entries() {
        return changeLog.recent(500).size();
    }

    private Category createCategory(String name) {
        return (Category) categoriesApi.create(Map.of("name", name)).getBody();
    }

    private long addTransaction(String description, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction("2026-05-01", description, 25, "debit", category)),
                "settings-history-test", Account.DEFAULT_ID);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private String categoryOf(long transactionId) {
        return transactions.findById(transactionId).orElseThrow().category();
    }

    private void setBudget(String category, double limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("monthly_limit", limit);
        budgetsApi.set(body);
    }

    @Test
    @DisplayName("renaming a category is one entry; undo renames it back everywhere the rename reached")
    void undoCategoryRename() {
        Category pets = createCategory("Pet care");
        long tx = addTransaction("PAWS & CLAWS", "Pet care");
        setBudget("Pet care", 80);
        rules.create("KIBBLE", "Pet care");
        memory.remember("PAWS & CLAWS", "Pet care", MerchantCategory.SOURCE_USER);

        categoriesApi.update(pets.id(), Map.of("name", "Animals"));
        assertThat(latest().summary()).isEqualTo("Renamed the category \"Pet care\" to \"Animals\"");
        assertThat(categoryOf(tx)).isEqualTo("Animals");

        // The transaction, then the category, budget, keyword rule and memory.
        assertThat(undoLatest()).isEqualTo(new UndoResult(1, 0, 0, 4));
        assertThat(categories.findById(pets.id()).orElseThrow().name()).isEqualTo("Pet care");
        assertThat(categoryOf(tx)).isEqualTo("Pet care");
        assertThat(budgets.findByCategory("Pet care")).isPresent();
        assertThat(rules.findAll()).extracting(KeywordRule::category).contains("Pet care").doesNotContain("Animals");
        assertThat(memory.findCatchAll("PAWS & CLAWS")).get().extracting(MerchantCategory::category).isEqualTo("Pet care");
    }

    @Test
    @DisplayName("undoing a category delete brings it back, its budget too, and moves its transactions back from Other")
    void undoCategoryDelete() {
        Category pets = createCategory("Pet care");
        long tx = addTransaction("PAWS & CLAWS", "Pet care");
        setBudget("Pet care", 80);

        categoriesApi.delete(pets.id());
        assertThat(latest().summary()).isEqualTo("Deleted the category \"Pet care\" (1 transaction moved to Other)");
        assertThat(categoryOf(tx)).isEqualTo("Other");

        assertThat(undoLatest()).isEqualTo(new UndoResult(1, 0, 0, 2));
        assertThat(categories.findById(pets.id())).get().extracting(Category::name).isEqualTo("Pet care");
        assertThat(categoryOf(tx)).isEqualTo("Pet care");
        assertThat(budgets.findByCategory("Pet care")).get().extracting(b -> b.monthlyLimit()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("a category made again since under the old name keeps it; undoing the delete skips the clash rather than failing")
    void undoSkipsUniqueClash() {
        Category pets = createCategory("Pet care");
        categoriesApi.delete(pets.id());
        long deleteEntry = latest().id();
        createCategory("Pet care");

        assertThat(changeLog.undo(deleteEntry).orElseThrow()).isEqualTo(new UndoResult(0, 1, 0, 0));
        assertThat(categories.findAll()).extracting(Category::name).containsOnlyOnce("Pet care");
    }

    @Test
    @DisplayName("a color change is recorded and undone; a rejected edit changes nothing and records nothing")
    void colorAndRejectedEdits() {
        Category pets = createCategory("Pet care");
        categoriesApi.update(pets.id(), Map.of("color", "#123456"));
        assertThat(latest().summary()).isEqualTo("Changed the color of the category \"Pet care\"");
        undoLatest();
        assertThat(categories.findById(pets.id()).orElseThrow().color()).isNull();

        int before = entries();
        Map<String, Object> bad = Map.of("name", "Animals", "color", "not-a-color");
        assertThat(categoriesApi.update(pets.id(), bad).getStatusCode().value()).isEqualTo(400);
        assertThat(categories.findById(pets.id()).orElseThrow().name()).isEqualTo("Pet care");   // the rename didn't land
        assertThat(entries()).isEqualTo(before);
    }

    @Test
    @DisplayName("setting, changing and removing a budget are each undoable; a budget changed again since is left")
    void budgets() {
        setBudget("Groceries", 400);
        assertThat(latest().summary()).isEqualTo("Set the budget for Groceries");
        long created = latest().id();

        setBudget("Groceries", 450);
        assertThat(latest().summary()).isEqualTo("Changed the budget for Groceries");
        // Undoing the first (create) now: the budget isn't as that left it, so it stays.
        assertThat(changeLog.undo(created).orElseThrow()).isEqualTo(new UndoResult(0, 1, 0, 0));

        undoLatest();
        assertThat(budgets.findByCategory("Groceries").orElseThrow().monthlyLimit()).isEqualTo(400);

        budgetsApi.delete(budgets.findByCategory("Groceries").orElseThrow().id());
        assertThat(latest().summary()).isEqualTo("Removed the budget for Groceries");
        undoLatest();
        assertThat(budgets.findByCategory("Groceries").orElseThrow().monthlyLimit()).isEqualTo(400);
    }

    @Test
    @DisplayName("adding and deleting a keyword rule are undoable")
    void keywordRules() {
        rulesApi.create(Map.of("keyword", "VET", "category", "Healthcare"));
        assertThat(latest().summary()).isEqualTo("Added the keyword rule \"VET\" → Healthcare");
        undoLatest();
        assertThat(rules.keywordExists("VET")).isFalse();

        KeywordRule rule = rules.create("VET", "Healthcare");
        rulesApi.delete(rule.id());
        assertThat(latest().summary()).isEqualTo("Deleted the keyword rule \"VET\" → Healthcare");
        undoLatest();
        assertThat(rules.findById(rule.id())).get().extracting(KeywordRule::keyword).isEqualTo("VET");
    }

    @Test
    @DisplayName("merchant memory: a rule, forgetting one, forgetting all and importing are undoable; hits counted since don't block it")
    void merchantMemory() {
        memoryApi.saveRule(Map.of("merchant_key", "corner coffee", "category", "Dining & Coffee"));
        assertThat(latest().summary()).isEqualTo("Merchant memory: CORNER COFFEE → Dining & Coffee");
        MerchantCategory rule = memory.findCatchAll("CORNER COFFEE").orElseThrow();
        memory.recordHits(Map.of(rule.id(), 3));
        undoLatest();
        assertThat(memory.findCatchAll("CORNER COFFEE")).isEmpty();

        memory.remember("CORNER COFFEE", "Dining & Coffee", MerchantCategory.SOURCE_USER);
        memory.remember("BOOKS LTD", "Shopping", MerchantCategory.SOURCE_USER);
        memoryApi.forget(memory.findCatchAll("BOOKS LTD").orElseThrow().id());
        assertThat(latest().summary()).isEqualTo("Forgot BOOKS LTD → Shopping in merchant memory");
        undoLatest();
        assertThat(memory.findCatchAll("BOOKS LTD")).isPresent();

        memoryApi.forgetAll();
        assertThat(latest().summary()).isEqualTo("Forgot all of merchant memory (2 rules)");
        assertThat(undoLatest().settingsRestored()).isEqualTo(2);
        assertThat(memory.findAll()).hasSize(2);

        memoryApi.importRules(new MockMultipartFile("file", "rules.csv", "text/csv",
                "merchant_key,category\nTAXI CO,Transportation\n".getBytes(StandardCharsets.UTF_8)), "replace");
        assertThat(latest().summary()).isEqualTo("Replaced merchant memory with 1 merchant rule from rules.csv");
        undoLatest();
        assertThat(memory.findAll()).extracting(MerchantCategory::merchantKey)
                .containsExactlyInAnyOrder("CORNER COFFEE", "BOOKS LTD");
    }

    @Test
    @DisplayName("merchant names: naming, merging, clearing and turning down a suggestion are undoable")
    void merchantNames() {
        namesApi.rename(Map.of("keys", List.of("AMAZON.COM"), "name", "Amazon"));
        assertThat(latest().summary()).isEqualTo("Named AMAZON.COM \"Amazon\"");

        namesApi.rename(Map.of("keys", List.of("AMAZON.COM", "AMZN MKTP US"), "name", "Amazon shop"));
        assertThat(latest().summary()).isEqualTo("Merged 2 merchants as \"Amazon shop\"");
        undoLatest();
        assertThat(names.load().byKey()).containsExactly(Map.entry("AMAZON.COM", "Amazon"));

        namesApi.clear(Map.of("keys", List.of("AMAZON.COM")));
        assertThat(latest().summary()).isEqualTo("Took the name off AMAZON.COM");
        undoLatest();
        assertThat(names.find("AMAZON.COM")).contains("Amazon");

        namesApi.dismiss(Map.of("stem", "AMAZON"));
        undoLatest();
        assertThat(names.dismissedStems()).isEmpty();
    }

    @Test
    @DisplayName("saving what's already there records nothing")
    void noOpsAreNotRecorded() {
        setBudget("Groceries", 400);
        int before = entries();
        setBudget("Groceries", 400);
        namesApi.clear(Map.of("keys", List.of("NEVER NAMED")));
        assertThat(entries()).isEqualTo(before);
    }
}
