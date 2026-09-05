package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PredictionsPayload;
import com.spendinganalyzer.dto.PredictionsResponse;
import com.spendinganalyzer.repository.PredictionsCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit-level: every collaborator is a plain mock, so these prove the account key is threaded
 * through correctly without needing a real database. {@link PredictionsCacheRepositoryTest}
 * proves the storage side — that two accounts' cached rows genuinely don't collide.
 */
class InsightsServiceTest {

    private StatsService stats;
    private AnthropicService anthropic;
    private PredictionsCacheRepository cache;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        stats = mock(StatsService.class);
        anthropic = mock(AnthropicService.class);
        cache = mock(PredictionsCacheRepository.class);
        service = new InsightsService(stats, anthropic, cache, new ObjectMapper());
    }

    private static CategoryMonthlySeries series(String category) {
        return new CategoryMonthlySeries(category, List.of(new MonthlyTotal("2026-06", 100.0)),
                100.0, 100.0, 100.0, 100.0);
    }

    // --- reading the cache --------------------------------------------------------

    @Test
    @DisplayName("reads the cache for the account asked about, not some other one")
    void getCachedPredictionsPassesAccountIdThrough() {
        when(cache.find(7L)).thenReturn(Optional.empty());
        when(cache.find(9L)).thenReturn(Optional.of(new PredictionsCacheRepository.CachedEntry(
                "{\"summary\":\"s\",\"predictions\":[],\"recommendations\":[]}", "2026-06-01T00:00:00Z")));

        assertThat(service.getCachedPredictions(7L).predictions()).isNull();
        assertThat(service.getCachedPredictions(9L).predictions()).isNotNull();
        // The two accounts must hit their own cache row — sharing one would be the exact bug
        // this whole change exists to fix.
        verify(cache).find(7L);
        verify(cache).find(9L);
    }

    @Test
    @DisplayName("null accountId means every account combined, same as everywhere else in the app")
    void nullAccountIdMeansAllAccounts() {
        when(cache.find(isNull())).thenReturn(Optional.empty());

        PredictionsResponse response = service.getCachedPredictions(null);

        assertThat(response.predictions()).isNull();
        verify(cache).find(isNull());
    }

    @Test
    @DisplayName("no cached row gives a null payload rather than throwing")
    void missingCacheGivesNullPayload() {
        when(cache.find(any())).thenReturn(Optional.empty());

        PredictionsResponse response = service.getCachedPredictions(1L);

        assertThat(response.predictions()).isNull();
        assertThat(response.generatedAt()).isNull();
    }

    // --- writing the cache --------------------------------------------------------

    @Test
    @DisplayName("refreshing for one account stores it under that account, not a shared slot")
    void refreshStoresUnderTheAccountItWasGeneratedFor() {
        when(stats.computeMonthlyCategorySeries(eq(3L), any())).thenReturn(List.of(series("Groceries")));
        when(stats.computeMonthlyTotals(eq(3L), any())).thenReturn(List.of(new MonthlyTotal("2026-06", 100.0)));
        when(anthropic.generatePredictions(any(), any()))
                .thenReturn(new PredictionsPayload("summary", List.of(), List.of()));

        service.refreshPredictions(3L);

        ArgumentCaptor<Long> accountIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cache).upsert(accountIdCaptor.capture(), any(), any());
        assertThat(accountIdCaptor.getValue()).isEqualTo(3L);
    }

    @Test
    @DisplayName("refreshing for null (all accounts) stores it that way too")
    void refreshForAllAccountsStoresNull() {
        when(stats.computeMonthlyCategorySeries(isNull(), any())).thenReturn(List.of(series("Groceries")));
        when(stats.computeMonthlyTotals(isNull(), any())).thenReturn(List.of(new MonthlyTotal("2026-06", 100.0)));
        when(anthropic.generatePredictions(any(), any()))
                .thenReturn(new PredictionsPayload("summary", List.of(), List.of()));

        service.refreshPredictions(null);

        verify(cache).upsert(isNull(), any(), any());
    }

    @Test
    @DisplayName("always uses full history, regardless of which account it's for")
    void alwaysUsesFullHistory() {
        when(stats.computeMonthlyCategorySeries(eq(5L), eq(DateRange.ALL))).thenReturn(List.of(series("Groceries")));
        when(stats.computeMonthlyTotals(eq(5L), eq(DateRange.ALL))).thenReturn(List.of());
        when(anthropic.generatePredictions(any(), any()))
                .thenReturn(new PredictionsPayload("summary", List.of(), List.of()));

        service.refreshPredictions(5L);

        verify(stats).computeMonthlyCategorySeries(5L, DateRange.ALL);
        verify(stats).computeMonthlyTotals(5L, DateRange.ALL);
    }

    @Test
    @DisplayName("an account with no categorized spending skips the model entirely")
    void emptySeriesSkipsTheModel() {
        when(stats.computeMonthlyCategorySeries(any(), any())).thenReturn(List.of());
        when(stats.computeMonthlyTotals(any(), any())).thenReturn(List.of());

        PredictionsResponse response = service.refreshPredictions(2L);

        verify(anthropic, never()).generatePredictions(any(), any());
        assertThat(response.predictions().predictions()).isEmpty();
        verify(cache).upsert(eq(2L), any(), any());
    }
}
