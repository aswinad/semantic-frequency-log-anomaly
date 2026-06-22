package com.loganomaly.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record AppConfig(
        DatasetMode datasetMode,
        DatasetAction datasetAction,
        String openSearchUrl,
        Optional<String> openSearchUsername,
        Optional<String> openSearchPassword,
        String openSearchIndex,
        boolean openSearchIntegrationEnabled,
        String embeddingProviderName,
        OpenAiConfig openAi,
        OpenStackConfig openStack,
        BglConfig bgl,
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
                DatasetMode.parse(dotenv.get("DATASET_MODE", "synthetic")),
                DatasetAction.parse(dotenv.get("DATASET_ACTION", "evaluate")),
                dotenv.get("OPENSEARCH_URL", "http://localhost:9200"),
                dotenv.getOptional("OPENSEARCH_USERNAME"),
                dotenv.getOptional("OPENSEARCH_PASSWORD"),
                dotenv.get("OPENSEARCH_INDEX", "log-anomaly-synthetic"),
                dotenv.getBoolean("OPENSEARCH_INTEGRATION_ENABLED", false),
                dotenv.get("EMBEDDING_PROVIDER", "deterministic-synthetic-v1"),
                new OpenAiConfig(
                        dotenv.getOptional("OPENAI_API_KEY"),
                        dotenv.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
                        dotenv.getInt("OPENAI_EMBEDDING_DIMENSIONS", 1536)
                ),
                new OpenStackConfig(
                        Path.of(dotenv.get("OPENSTACK_LOGHUB_DIR", "data/loghub/openstack")),
                        dotenv.get("OPENSTACK_INDEX", "log-anomaly-openstack"),
                        dotenv.getBoolean("OPENSTACK_RECREATE_INDEX", false),
                        Path.of(dotenv.get("OPENSTACK_EMBEDDING_CACHE", "target/openstack-embedding-cache.jsonl")),
                        dotenv.getInt("OPENSTACK_BATCH_SIZE", 64),
                        dotenv.getInt("OPENSTACK_INDEX_BATCH_SIZE", 1000),
                        java.time.Instant.parse(dotenv.get("OPENSTACK_EXPERIMENT_ANCHOR", "2026-01-01T00:00:00Z")),
                        Duration.ofMinutes(dotenv.getInt("OPENSTACK_SHORT_WINDOW_MINUTES", 15)),
                        Duration.ofHours(dotenv.getInt("OPENSTACK_BASELINE_WINDOW_HOURS", 24))
                ),
                new BglConfig(
                        Path.of(dotenv.get("BGL_LOGHUB_FILE", "data/loghub/bgl/BGL.log")),
                        dotenv.get("BGL_INDEX", "log-anomaly-bgl"),
                        dotenv.getBoolean("BGL_RECREATE_INDEX", false),
                        Path.of(dotenv.get("BGL_EMBEDDING_CACHE", "target/bgl-embedding-cache.jsonl")),
                        dotenv.getInt("BGL_BATCH_SIZE", 64),
                        dotenv.getInt("BGL_INDEX_BATCH_SIZE", 1000),
                        Duration.ofMinutes(dotenv.getInt("BGL_SHORT_WINDOW_MINUTES", 15)),
                        Duration.ofHours(dotenv.getInt("BGL_BASELINE_WINDOW_HOURS", 24)),
                        Duration.ofMinutes(dotenv.getInt("BGL_EVAL_BUCKET_MINUTES", 5)),
                        BglCandidateMode.parse(dotenv.get("BGL_CANDIDATE_MODE", "filtered")),
                        dotenv.getBoolean("BGL_VERBOSE_ROW_LOGGING", false),
                        dotenv.getInt("BGL_MIN_HISTORICAL_SUPPORT", 5),
                        dotenv.getInt("BGL_MIN_ALERT_SHORT_SUPPORT", 3),
                        parseDoubleList(dotenv.getOptional("BGL_SIMILARITY_SWEEP"), List.of(0.70, 0.75, 0.80, 0.85, 0.90)),
                        BglEvalRangeMode.parse(dotenv.get("BGL_EVAL_RANGE_MODE", "contiguous")),
                        dotenv.getOptional("BGL_EVAL_START").map(java.time.Instant::parse),
                        Duration.ofDays(dotenv.getInt("BGL_EVAL_DURATION_DAYS", 14)),
                        Path.of(dotenv.get("BGL_ABLATION_CACHE", "target/bgl-ablation-cache.jsonl")),
                        dotenv.getBoolean("BGL_CLEAR_ABLATION_CACHE", false),
                        dotenv.getBoolean("BGL_ABLATION_PARALLEL", false),
                        Math.max(1, dotenv.getInt("BGL_ABLATION_MAX_WORKERS", 2))
                ),
                experiment,
                new ReportConfig(
                        dotenv.getBoolean("REPORT_EXCEL_ENABLED", false),
                        dotenv.get("REPORT_OUTPUT_DIR", "reports"),
                        dotenv.get("REPORT_FILE_PREFIX", "semantic-frequency-paper-test")
                )
        );
    }

    private static List<Double> parseDoubleList(Optional<String> raw, List<Double> defaults) {
        if (raw.isEmpty() || raw.orElseThrow().isBlank()) {
            return defaults;
        }
        return raw.orElseThrow().lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }
}
