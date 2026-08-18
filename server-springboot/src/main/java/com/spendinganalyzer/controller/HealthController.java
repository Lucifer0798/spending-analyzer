package com.spendinganalyzer.controller;

import com.spendinganalyzer.config.DotenvLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final DotenvLoader env;

    public HealthController(DotenvLoader env) {
        this.env = env;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String apiKey = env.get("ANTHROPIC_API_KEY", null);
        return Map.of("ok", true, "hasApiKey", apiKey != null && !apiKey.isBlank());
    }
}
