package com.spendinganalyzer.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helper for building JSON Schema maps for structured outputs (output_config.format),
 * and converting the finished map into the SDK's JsonOutputFormat.Schema type.
 */
final class JsonSchemaBuilder {

    private JsonSchemaBuilder() {}

    static Map<String, Object> string(String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        if (description != null) m.put("description", description);
        return m;
    }

    static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", values);
        return m;
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        return m;
    }

    static JsonOutputFormat.Schema toSchema(Map<String, Object> schemaMap) {
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        schemaMap.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }
}
