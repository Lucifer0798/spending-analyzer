package com.spendinganalyzer.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient(DotenvLoader env) {
        String apiKey = env.get("ANTHROPIC_API_KEY", null);
        if (apiKey != null && !apiKey.isBlank()) {
            return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        }
        // Falls back to ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / an `ant auth login` profile.
        return AnthropicOkHttpClient.fromEnv();
    }
}
