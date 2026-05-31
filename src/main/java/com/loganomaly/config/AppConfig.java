package com.loganomaly.config;

import java.time.Duration;
import java.util.Optional;

public record AppConfig(
        String openSearchUrl,
        Optional<String> openSearchUsername,
        Optional<String> openSearchPassword,
        String openSearchIndex,
        boolean openSearchIntegrationEnabled,
        String embeddingProviderName,
        ExperimentConfig experiment,
        ReportConfig report
) {
    public static AppConfig load() {
        return load(Dotenv.load());
    }

    public static AppConfig load(Dotenv dotenv) {
        ExperimentConfig experiment = new ExperimentConfig(
                Duration.ofMinutes(dotenv.getInt("EXPERIMENT_SHORT_WINDOW_MINUTES", 5)),
                Duration.ofMinutes(dotenv.getInt("EXPERIMENT_BASELINE_WINDOW_MINUTES", 55)),
                dotenv.getInt("EXPERIMENT_TOP_K", 5),
                dotenv.getInt("EXPERIMENT_NOVELTY_THRESHOLD", 3),
                dotenv.getDouble("EXPERIMENT_SIMILARITY_THRESHOLD", 0.85),
                dotenv.getDouble("EXPERIMENT_SPIKE_THRESHOLD", 2.0)
        );

        return new AppConfig(
                dotenv.get("OPENSEARCH_URL", "http://localhost:9200"),
                dotenv.getOptional("OPENSEARCH_USERNAME"),
                dotenv.getOptional("OPENSEARCH_PASSWORD"),
                dotenv.get("OPENSEARCH_INDEX", "log-anomaly-synthetic"),
                dotenv.getBoolean("OPENSEARCH_INTEGRATION_ENABLED", false),
                dotenv.get("EMBEDDING_PROVIDER", "deterministic-synthetic-v1"),
                experiment,
                new ReportConfig(
                        dotenv.getBoolean("REPORT_EXCEL_ENABLED", false),
                        dotenv.get("REPORT_OUTPUT_DIR", "reports"),
                        dotenv.get("REPORT_FILE_PREFIX", "semantic-frequency-paper-test"),
                        dotenv.get("LLM_EVALUATION_MODE", "placeholder")
                )
        );
    }
}
