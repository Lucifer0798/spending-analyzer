package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A free-form label a transaction can carry alongside its single category. */
public record Tag(long id, String name, @JsonProperty("created_at") String createdAt) {}
