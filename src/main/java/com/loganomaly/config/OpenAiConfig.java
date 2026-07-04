package com.loganomaly.config;

import java.util.Optional;

public record OpenAiConfig(
        Optional<String> apiKey,
        String embeddingModel,
        int embeddingDimensions
) {
}
