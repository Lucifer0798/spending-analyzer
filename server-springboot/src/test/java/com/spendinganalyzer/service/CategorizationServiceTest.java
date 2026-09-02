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

class CategorizationServiceTest {

    private TransactionRepository transactions;
    private CategoryRepository categories;
    private MerchantCategoryRepository memory;
    private AnthropicService anthropic;
    private CategorizationService service;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        categories = mock(CategoryRepository.class);
        memory = mock(MerchantCategoryRepository.class);
        anthropic = mock(AnthropicService.class);
        service = new CategorizationService(transactions, categories, memory, anthropic);

        when(categories.findAllNames()).thenReturn(List.of("Groceries", "Dining & Coffee", "Shopping", "Other"));
        when(memory.loadAll()).thenReturn(Map.of());
    }

    private static Transaction tx(long id, String description) {
        return new Transaction(id, "2026-05-01", description, 10.0, "debit",
                null, null, "batch", "2026-05-01", 1L, "Default");
    }

    /** A catch-all rule, which is what memory held before amount bands existed. */
    private static List<MerchantCategory> remembered(String key, String category, String source) {
        return List.of(new MerchantCategory(1, key, category,
                0, MerchantCategory.UNBOUNDED, source, 0, "2026-01-01", "2026-01-01"));
    }

    private void modelAnswers(Map<Long, String> answers) {
        when(anthropic.categorizeBatch(any())).thenAnswer(invocation -> {
            List<Transaction> batch = invocation.getArgument(0);
            List<AnthropicService.CategorizationEntry> entries = batch.stream()
                    .filter(t -> answers.containsKey(t.id()))
                    .map(t -> new AnthropicService.CategorizationEntry(t.id(), answers.get(t.id())))
                    .toList();
            return new AnthropicService.CategorizationResult(entries);
        });
    }

    @Test
    @DisplayName("nothing to do when no transactions are uncategorized")
    void doesNothingWhenNothingUncategorized() {
        when(transactions.findUncategorized()).thenReturn(List.of());

        CategorizeResponse response = service.categorizeAll();

        assertThat(response.categorized()).isZero();
        verify(anthropic, never()).categorizeBatch(any());
    }

    @Test
    @DisplayName("a remembered merchant is categorized without asking the model at all")
    void rememberedMerchantSkipsTheModel() {
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "STARBUCKS STORE 4521")));
        when(memory.loadAll()).thenReturn(Map.of(
                "STARBUCKS STORE", remembered("STARBUCKS STORE", "Dining & Coffee", "ai")));

        CategorizeResponse response = service.categorizeAll();

        verify(anthropic, never()).categorizeBatch(any());
        verify(transactions).updateCategory(1L, "Dining & Coffee", "cache");
        assertThat(response.fromMemory()).isEqualTo(1);
        assertThat(response.fromModel()).isZero();
        assertThat(response.merchantsQueried()).isZero();
    }

    @Test
    @DisplayName("memory matches across branches of the same merchant")
    void memoryMatchesAcrossBranches() {
        // A different store number must still hit the same remembered entry.
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "WHOLE FOODS MARKET #987")));
        when(memory.loadAll()).thenReturn(Map.of(
                "WHOLE FOODS MARKET", remembered("WHOLE FOODS MARKET", "Groceries", "user")));

        service.categorizeAll();

        verify(anthropic, never()).categorizeBatch(any());
        verify(transactions).updateCategory(1L, "Groceries", "cache");
    }

    @Test
    @DisplayName("an unknown merchant goes to the model and is then remembered")
    void unknownMerchantIsAskedOnceAndRemembered() {
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "NEW CAFE 123")));
        modelAnswers(Map.of(1L, "Dining & Coffee"));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions).updateCategory(1L, "Dining & Coffee", "ai");
        verify(memory).remember("NEW CAFE", "Dining & Coffee", MerchantCategory.SOURCE_AI);
        assertThat(response.fromModel()).isEqualTo(1);
        assertThat(response.merchantsQueried()).isEqualTo(1);
    }

    @Test
    @DisplayName("many transactions at one merchant are a single question to the model")
    void asksOncePerMerchantNotPerTransaction() {
        // Fifty coffees at the same shop should cost one answer, not fifty.
        when(transactions.findUncategorized()).thenReturn(List.of(
                tx(1, "STARBUCKS STORE 4521"),
                tx(2, "STARBUCKS STORE 8899"),
                tx(3, "STARBUCKS STORE 4521")));
        modelAnswers(Map.of(1L, "Dining & Coffee"));

        CategorizeResponse response = service.categorizeAll();

        ArgumentCaptor<List<Transaction>> sent = ArgumentCaptor.forClass(List.class);
        verify(anthropic, times(1)).categorizeBatch(sent.capture());
        assertThat(sent.getValue()).hasSize(1);

        // The single answer is applied to every transaction at that merchant.
        verify(transactions).updateCategory(1L, "Dining & Coffee", "ai");
        verify(transactions).updateCategory(2L, "Dining & Coffee", "ai");
        verify(transactions).updateCategory(3L, "Dining & Coffee", "ai");
        assertThat(response.fromModel()).isEqualTo(3);
        assertThat(response.merchantsQueried()).isEqualTo(1);
    }

    @Test
    @DisplayName("only the unknown merchants are sent when some are already remembered")
    void sendsOnlyTheUnknownMerchants() {
        when(transactions.findUncategorized()).thenReturn(List.of(
                tx(1, "STARBUCKS STORE 4521"),
                tx(2, "UNKNOWN SHOP")));
        when(memory.loadAll()).thenReturn(Map.of(
                "STARBUCKS STORE", remembered("STARBUCKS STORE", "Dining & Coffee", "ai")));
        modelAnswers(Map.of(2L, "Shopping"));

        CategorizeResponse response = service.categorizeAll();

        ArgumentCaptor<List<Transaction>> sent = ArgumentCaptor.forClass(List.class);
        verify(anthropic).categorizeBatch(sent.capture());
        assertThat(sent.getValue()).singleElement()
                .extracting(Transaction::description).isEqualTo("UNKNOWN SHOP");

        assertThat(response.fromMemory()).isEqualTo(1);
        assertThat(response.fromModel()).isEqualTo(1);
    }

    @Test
    @DisplayName("a remembered category that no longer exists falls through to the model")
    void staleRememberedCategoryIsIgnored() {
        // The user deleted the custom category this entry points at; reusing it would
        // write a category that is no longer valid.
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "PET SHOP")));
        when(memory.loadAll()).thenReturn(Map.of(
                "PET SHOP", remembered("PET SHOP", "Deleted Category", "user")));
        modelAnswers(Map.of(1L, "Other"));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions, never()).updateCategory(anyLong(), eq("Deleted Category"), any());
        verify(anthropic).categorizeBatch(any());
        assertThat(response.fromModel()).isEqualTo(1);
    }

    @Test
    @DisplayName("a category the model invents is not written or remembered")
    void ignoresCategoriesOutsideTheKnownSet() {
        when(transactions.findUncategorized()).thenReturn(List.of(tx(1, "ODD MERCHANT")));
        modelAnswers(Map.of(1L, "Not A Real Category"));

        CategorizeResponse response = service.categorizeAll();

        verify(transactions, never()).updateCategory(anyLong(), any(), any());
        verify(memory, never()).remember(any(), any(), any());
        assertThat(response.categorized()).isZero();
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
