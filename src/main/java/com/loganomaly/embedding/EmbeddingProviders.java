package com.loganomaly.embedding;

import com.loganomaly.config.AppConfig;

import java.util.Locale;

public final class EmbeddingProviders {
    private EmbeddingProviders() {
    }

    public static EmbeddingProvider fromConfig(AppConfig config) {
        String provider = config.embeddingProviderName().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "deterministic-synthetic-v1", "deterministic" -> new DeterministicEmbeddingProvider();
            case "openai", "openai-embedding" -> new OpenAIEmbeddingProvider(config.openAi());
            default -> throw new IllegalArgumentException("Unsupported EMBEDDING_PROVIDER: " + config.embeddingProviderName());
        };
    }
}
