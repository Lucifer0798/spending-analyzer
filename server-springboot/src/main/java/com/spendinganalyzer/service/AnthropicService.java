package com.spendinganalyzer.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spendinganalyzer.config.DotenvLoader;
import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.PredictionsPayload;
import com.spendinganalyzer.model.Categories;
import com.spendinganalyzer.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnthropicService {

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public AnthropicService(AnthropicClient client, ObjectMapper objectMapper, DotenvLoader env) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = env.get("ANTHROPIC_MODEL", "claude-opus-5");
    }

    public record CategorizationEntry(long id, String category) {}

    public record CategorizationResult(List<CategorizationEntry> categorizations) {}

    public CategorizationResult categorizeBatch(List<Transaction> batch) {
        List<Map<String, Object>> items = batch.stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.id(),
                        "description", t.description(),
                        "amount", t.amount(),
                        "type", t.type()
                ))
                .toList();

        String itemsJson = writeJson(items);

        Map<String, Object> categorizationItem = JsonSchemaBuilder.object(
                Map.of(
                        "id", JsonSchemaBuilder.integer(),
                        "category", JsonSchemaBuilder.enumOf(Categories.ALL)
                ),
                List.of("id", "category")
        );
        Map<String, Object> schemaMap = JsonSchemaBuilder.object(
                Map.of("categorizations", JsonSchemaBuilder.array(categorizationItem)),
                List.of("categorizations")
        );

        OutputConfig outputConfig = OutputConfig.builder()
                .effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder().schema(JsonSchemaBuilder.toSchema(schemaMap)).build())
                .build();

        String prompt = """
                Categorize each of these bank transactions into exactly one of these categories: %s.

                Use the transaction description and amount to infer the merchant/purpose. "Income" is for salary/deposits/refunds (type=credit). "Transfer" is for account transfers, credit card payments, or Venmo/Zelle-style person-to-person transfers. Use "Other" only when nothing else plausibly fits.

                Transactions (JSON):
                %s

                Return a categorization for every id listed above.
                """.formatted(String.join(", ", Categories.ALL), itemsJson);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096)
                .outputConfig(outputConfig)
                .addUserMessage(prompt)
                .build();

        Message response = client.messages().create(params);
        String text = extractText(response);

        try {
            return objectMapper.readValue(text, CategorizationResult.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse categorization response: " + e.getMessage(), e);
        }
    }

    public PredictionsPayload generatePredictions(List<CategoryMonthlySeries> series, List<MonthlyTotal> monthlyTotals) {
        List<Map<String, Object>> statsForModel = series.stream()
                .map(s -> Map.<String, Object>of(
                        "category", s.category(),
                        "monthlyHistory", s.months(),
                        "statisticalTrendProjection", s.linearTrendNextMonth(),
                        "threeMonthMovingAverage", s.movingAverage3mo(),
                        "lastMonthTotal", s.lastMonthTotal()
                ))
                .toList();

        String statsJson = writeJson(statsForModel);
        String monthlyTotalsJson = writeJson(monthlyTotals);

        Map<String, Object> predictionItem = JsonSchemaBuilder.object(
                Map.of(
                        "category", JsonSchemaBuilder.string(null),
                        "predicted_next_month", JsonSchemaBuilder.number(),
                        "trend", JsonSchemaBuilder.enumOf(List.of("increasing", "decreasing", "stable")),
                        "confidence", JsonSchemaBuilder.enumOf(List.of("low", "medium", "high")),
                        "rationale", JsonSchemaBuilder.string("one sentence explaining the prediction")
                ),
                List.of("category", "predicted_next_month", "trend", "confidence", "rationale")
        );

        Map<String, Object> recommendationItem = JsonSchemaBuilder.object(
                Map.of(
                        "category", JsonSchemaBuilder.string(null),
                        "insight", JsonSchemaBuilder.string("what pattern was observed"),
                        "suggested_action", JsonSchemaBuilder.string("a specific, actionable step to reduce spending"),
                        "potential_monthly_savings", JsonSchemaBuilder.number()
                ),
                List.of("category", "insight", "suggested_action", "potential_monthly_savings")
        );

        Map<String, Object> schemaMap = JsonSchemaBuilder.object(
                Map.of(
                        "summary", JsonSchemaBuilder.string("2-3 sentence plain-language overview of spending trends"),
                        "predictions", JsonSchemaBuilder.array(predictionItem),
                        "recommendations", JsonSchemaBuilder.array(recommendationItem)
                ),
                List.of("summary", "predictions", "recommendations")
        );

        OutputConfig outputConfig = OutputConfig.builder()
                .effort(OutputConfig.Effort.MEDIUM)
                .format(JsonOutputFormat.builder().schema(JsonSchemaBuilder.toSchema(schemaMap)).build())
                .build();

        String prompt = """
                You are a personal finance analyst. Below is a user's spending history broken down by category and month, plus two statistical baselines (a linear trend projection and a 3-month moving average) already computed from the data.

                Overall monthly spend totals: %s

                Per-category monthly history and statistical baselines: %s

                Tasks:
                1. For each category, predict next month's spend. Ground your prediction in the statistical baselines provided, but use judgment (e.g. smooth out one-off spikes, weight recent months more if there's a clear trend).
                2. Identify the categories with the strongest growth trend or the biggest waste/reduction opportunity, and write specific, actionable recommendations for reducing spending. Reference actual amounts from the data. Only include recommendations grounded in the data shown — do not invent categories or numbers.
                3. Write a short overall summary of the spending picture.
                """.formatted(monthlyTotalsJson, statsJson);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000)
                .outputConfig(outputConfig)
                .addUserMessage(prompt)
                .build();

        Message response = client.messages().create(params);
        String text = extractText(response);

        try {
            return objectMapper.readValue(text, PredictionsPayload.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse predictions response: " + e.getMessage(), e);
        }
    }

    private String extractText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No text response from model."));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
