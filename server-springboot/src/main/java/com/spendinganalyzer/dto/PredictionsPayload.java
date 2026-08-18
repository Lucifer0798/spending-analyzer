package com.spendinganalyzer.dto;

import java.util.List;

public record PredictionsPayload(
        String summary,
        List<Prediction> predictions,
        List<Recommendation> recommendations
) {}
