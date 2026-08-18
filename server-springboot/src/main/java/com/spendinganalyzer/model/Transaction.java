package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Transaction(
        long id,
        String date,
        String description,
        double amount,
        String type,
        String category,
        @JsonProperty("category_source") String categorySource,
        @JsonProperty("upload_batch_id") String uploadBatchId,
        @JsonProperty("created_at") String createdAt
) {}
