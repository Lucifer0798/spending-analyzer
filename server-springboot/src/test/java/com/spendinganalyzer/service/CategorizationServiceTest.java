package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Memory first, then the built-in rules, and an honest blank when neither knows. */
class CategorizationServiceTest {

    private TransactionRepository transactions;
    private CategoryRepository categories;
    private MerchantCategoryRepository memory;
    private CategorizationService service;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        categories = mock(CategoryRepository.class);
        memory = mock(MerchantCategoryRepository.class);
        service = new CategorizationService(transactions, categories, memory);

        when(categories.findAllNames()).thenReturn(List.of(
                "Groceries", "Dining & Coffee", "Shopping", "Income", "Transfer", "Other", "Pets"));
        when(memory.loadAll()).thenReturn(Map.of());
    }

    private static Transaction tx(long id, String description) {
        return tx(id, description, "debit");
    }

    private static Transaction tx(long id, String description, String type) {
        return new Transaction(id, "2026-05-01", description, 10.0, type,
                null, null, "batch", "2026-05-01", 1L, "Default", "USD", null, null);
    }

    /** A catch-all rule, which is what memory held before amount bands existed. */
    private static List<MerchantCategory> remembered(String key, String category, String source) {
        return List.of(new MerchantCategory(1, key, category,
                0, MerchantCategory.UNBOUNDED, source, 0, "2026-01-01", "2026-01-01"));
    }

    @Test
    @DisplayName("nothing to do when no transactions are uncategorized")
    void doesNothingWhenNothingUncategorized() {
        when(transactions.findUncategorized()).thenReturn(List.of());

        CategorizeResponse response = service.categorizeAll();

        assertThat(response.categorized()).isZero();
        verify(transactions, never()).updateCategory(anyLong(), any(), any());
    }

    @Test
    @DisplayName("a remembered merchant is categorized from memory, ahead of any rule")
    void memoryOutranksRules() {
        // A rule would call this Dining & Coffee; the user taught otherwise, and that wins.
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "STARBUCKS STORE 4521")));
        when(memory.loadAll()).thenReturn(Map.of(
                "STARBUCKS STORE", remembered("STARBUCKS STORE", "Groceries", "user")));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions).updateCategory(1L, "Groceries", "cache");
        assertThat(response.fromMemory()).isEqualTo(1);
        assertThat(response.fromRules()).isZero();
    }

    @Test
    @DisplayName("memory matches across branches of the same merchant")
    void memoryMatchesAcrossBranches() {
        // A different store number must still hit the same remembered entry.
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "WHOLE FOODS MARKET #987")));
        when(memory.loadAll()).thenReturn(Map.of(
                "WHOLE FOODS MARKET", remembered("WHOLE FOODS MARKET", "Groceries", "user")));

        service.categorizeAll();

        verify(transactions).updateCategory(1L, "Groceries", "cache");
    }

    @Test
    @DisplayName("an unknown merchant a rule recognises is categorized as 'rule' and not written to memory")
    void rulesCategorizeWithoutTouchingMemory() {
        when(transactions.findUncategorized()).thenReturn(List.of(
                tx(1, "NEW CORNER CAFE 123"),
                tx(2, "ACME PAYROLL", "credit")));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions).updateCategory(1L, "Dining & Coffee", "rule");
        verify(transactions).updateCategory(2L, "Income", "rule");
        // Rules are re-run on every import, so improving one improves every later import --
        // a remembered copy would freeze today's answer.
        verify(memory, never()).remember(any(), any(), any());
        assertThat(response.fromRules()).isEqualTo(2);
        assertThat(response.unmatched()).isZero();
    }

    @Test
    @DisplayName("a debit nothing recognises stays uncategorized rather than being filed under Other")
    void unknownDebitIsLeftBlank() {
        when(transactions.findUncategorized()).thenReturn(List.of(
                tx(1, "ZQX HOLDINGS 4471"),
                tx(2, "SUPERMARKET 12")));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions, never()).updateCategory(eq(1L), any(), any());
        verify(transactions).updateCategory(2L, "Groceries", "rule");
        assertThat(response.categorized()).isEqualTo(1);
        assertThat(response.unmatched()).isEqualTo(1);
        assertThat(response.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("a remembered category that no longer exists falls through to the rules")
    void staleRememberedCategoryIsIgnored() {
        // The user deleted the custom category this entry points at; reusing it would
        // write a category that is no longer valid.
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "AMAZON MKTPLACE")));
        when(memory.loadAll()).thenReturn(Map.of(
                "AMAZON MKTPLACE", remembered("AMAZON MKTPLACE", "Deleted Category", "user")));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions, never()).updateCategory(anyLong(), eq("Deleted Category"), any());
        verify(transactions).updateCategory(1L, "Shopping", "rule");
        assertThat(response.fromRules()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rule pointing at a built-in category that has been removed is skipped, not written")
    void ruleForMissingCategoryIsSkipped() {
        when(categories.findAllNames()).thenReturn(List.of("Groceries", "Other"));   // no Dining & Coffee
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "CORNER CAFE")));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions, never()).updateCategory(anyLong(), any(), any());
        assertThat(response.unmatched()).isEqualTo(1);
    }

    @Test
    @DisplayName("memory hits are counted so the management view can show them")
    void recordsMemoryHitCounts() {
        when(transactions.findUncategorized()).thenReturn(List.of(
                tx(1, "STARBUCKS STORE 4521"),
                tx(2, "STARBUCKS STORE 8899")));
        when(memory.loadAll()).thenReturn(Map.of(
                "STARBUCKS STORE", remembered("STARBUCKS STORE", "Dining & Coffee", "ai")));

        service.categorizeAll();

        ArgumentCaptor<Map<Long, Integer>> hits = ArgumentCaptor.forClass(Map.class);
        verify(memory).recordHits(hits.capture());
        // Keyed by the rule that answered, so a merchant with several bands credits only one.
        assertThat(hits.getValue()).containsEntry(1L, 2);
    }
}
