package com.spendinganalyzer.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PredictionsPayload;
import com.spendinganalyzer.dto.PredictionsResponse;
import com.spendinganalyzer.repository.PredictionsCacheRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class InsightsService {

    private final StatsService statsService;
    private final AnthropicService anthropicService;
    private final PredictionsCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;

    public InsightsService(
            StatsService statsService,
            AnthropicService anthropicService,
            PredictionsCacheRepository cacheRepository,
            ObjectMapper objectMapper
    ) {
        this.statsService = statsService;
        this.anthropicService = anthropicService;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
    }

    public PredictionsResponse getCachedPredictions() {
        return cacheRepository.find()
                .map(entry -> new PredictionsResponse(parsePayload(entry.payload()), entry.generatedAt()))
                .orElse(new PredictionsResponse(null, null));
    }

    public PredictionsResponse refreshPredictions(Long accountId) {
        List<CategoryMonthlySeries> series = statsService.computeMonthlyCategorySeries(accountId);
        List<MonthlyTotal> monthlyTotals = statsService.computeMonthlyTotals(accountId);

        PredictionsPayload payload;
        if (series.isEmpty()) {
            payload = new PredictionsPayload("Not enough categorized spending data yet.", List.of(), List.of());
        } else {
            payload = anthropicService.generatePredictions(series, monthlyTotals);
        }

        String generatedAt = Instant.now().toString();
        cacheRepository.upsert(writeJson(payload), generatedAt);
        return new PredictionsResponse(payload, generatedAt);
    }

    private PredictionsPayload parsePayload(String json) {
        try {
            return objectMapper.readValue(json, PredictionsPayload.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Corrupt predictions cache entry: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }
}
