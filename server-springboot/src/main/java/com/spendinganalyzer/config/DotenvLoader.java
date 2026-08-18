package com.spendinganalyzer.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal .env reader so the Spring Boot app can pick up ANTHROPIC_API_KEY / ANTHROPIC_MODEL
 * from a local .env file, the same convention used by the original Node backend, without pulling
 * in an extra dependency.
 */
@Component
public class DotenvLoader {

    private final Map<String, String> values = new HashMap<>();

    public DotenvLoader() {
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String rawLine : Files.readAllLines(envFile)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = unquote(line.substring(eq + 1).trim());
                    values.put(key, value);
                }
            } catch (IOException ignored) {
                // .env is optional
            }
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            boolean dq = value.startsWith("\"") && value.endsWith("\"");
            boolean sq = value.startsWith("'") && value.endsWith("'");
            if (dq || sq) return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public String get(String key, String defaultValue) {
        String fromFile = values.get(key);
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        String fromOsEnv = System.getenv(key);
        if (fromOsEnv != null && !fromOsEnv.isBlank()) return fromOsEnv;
        return defaultValue;
    }
}
