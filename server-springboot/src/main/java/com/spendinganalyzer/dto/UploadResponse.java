package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadResponse(
        String batchId,
        int inserted,
        int preCategorized,
        @JsonProperty("skippedDuplicates") int skippedDuplicates,
        @JsonProperty("parsed") int parsed,
        @JsonProperty("accountId") long accountId,
        @JsonProperty("accountName") String accountName
) {}
