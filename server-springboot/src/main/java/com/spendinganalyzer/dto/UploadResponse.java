package com.spendinganalyzer.dto;

public record UploadResponse(String batchId, int inserted, int preCategorized) {}
