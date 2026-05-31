package com.loganomaly.experiment;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.opensearch.LogDocument;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SyntheticLogDataset {
    public static final Instant OBSERVED_AT = Instant.parse("2026-01-01T01:00:00Z");

    private static final EmbeddingProvider EMBEDDINGS = new DeterministicEmbeddingProvider();

    private SyntheticLogDataset() {
    }

    public static int dimensions() {
        return EMBEDDINGS.dimensions();
    }

    public static List<LogDocument> historicalLogs() {
        List<LogDocument> logs = new ArrayList<>();

        addRecurring(logs, "exact-old", "payments", "database-connection-timeout", "db-connectivity",
                "exact-repeated-baseline", "Database connection timeout during transaction execution",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 8, Duration.ofMinutes(6));
        addRecurring(logs, "exact-new", "payments", "database-connection-timeout", "db-connectivity",
                "exact-repeated-short-window", "Database connection timeout during transaction execution",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 5, Duration.ofMinutes(1));

        addRecurring(logs, "db-base-timeout", "payments", "database-connection-timeout", "db-connectivity",
                "paraphrased-family-baseline", "Database connection timeout during transaction execution",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 2, Duration.ofMinutes(15));
        addRecurring(logs, "db-base-jdbc", "orders", "jdbc-connection-acquire", "db-connectivity",
                "paraphrased-family-baseline", "Unable to acquire JDBC connection from pool",
                OBSERVED_AT.minus(Duration.ofMinutes(50)), 2, Duration.ofMinutes(15));
        addRecurring(logs, "db-base-sql", "billing", "sql-connection-refused", "db-connectivity",
                "paraphrased-family-baseline", "SQL connection refused by downstream database",
                OBSERVED_AT.minus(Duration.ofMinutes(45)), 2, Duration.ofMinutes(15));
        addRecurring(logs, "db-base-pool", "inventory", "connection-pool-exhausted", "db-connectivity",
                "paraphrased-family-baseline", "Connection pool exhausted while processing request",
                OBSERVED_AT.minus(Duration.ofMinutes(40)), 2, Duration.ofMinutes(15));

        addRecurring(logs, "db-spike-timeout", "payments", "database-connection-timeout", "db-connectivity",
                "known-semantic-spike-short-window", "Database connection timeout during transaction execution",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofMinutes(1));
        addRecurring(logs, "db-spike-jdbc", "orders", "jdbc-connection-acquire", "db-connectivity",
                "known-semantic-spike-short-window", "Unable to acquire JDBC connection from pool",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(30));
        addRecurring(logs, "db-spike-sql", "billing", "sql-connection-refused", "db-connectivity",
                "known-semantic-spike-short-window", "SQL connection refused by downstream database",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(30));
        addRecurring(logs, "db-spike-pool", "inventory", "connection-pool-exhausted", "db-connectivity",
                "known-semantic-spike-short-window", "Connection pool exhausted while processing request",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(30));
        addRecurring(logs, "db-spike-rollback", "payments", "transaction-rollback-db-dependency", "db-connectivity",
                "known-semantic-spike-short-window", "Transaction rollback caused by database dependency failure",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(30));

        addRecurring(logs, "cache-base", "cache", "cache-eviction-retry", "cache-corruption",
                "critical-compound-baseline", "Cache eviction retry threshold exceeded",
                OBSERVED_AT.minus(Duration.ofMinutes(45)), 1, Duration.ofMinutes(1));
        addRecurring(logs, "cache-spike", "cache", "cache-sync-corruption", "cache-corruption",
                "critical-compound-short-window", "Distributed cache synchronization corruption detected",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 12, Duration.ofSeconds(25));

        addRecurring(logs, "noise", "orders", "routine-heartbeat", "routine-operations",
                "normal-operational-noise", "Order polling heartbeat completed",
                OBSERVED_AT.minus(Duration.ofMinutes(60)), 30, Duration.ofMinutes(2));
        return logs;
    }

    public static List<ScenarioProbe> probes() {
        return List.of(
                new ScenarioProbe(
                        "A. Exact Repeated Error",
                        "database-connection-timeout",
                        "db-connectivity",
                        OBSERVED_AT,
                        "payments",
                        "Database connection timeout during transaction execution",
                        EMBEDDINGS.embed("Database connection timeout during transaction execution"),
                        AnomalyClass.SURGE_ANOMALY
                ),
                new ScenarioProbe(
                        "B. Paraphrased Failure Family",
                        "jdbc-connection-acquire",
                        "db-connectivity",
                        OBSERVED_AT,
                        "orders",
                        "Unable to acquire JDBC connection from pool",
                        EMBEDDINGS.embed("Unable to acquire JDBC connection from pool"),
                        AnomalyClass.SURGE_ANOMALY
                ),
                new ScenarioProbe(
                        "C. Novel Semantic Event",
                        "auth-delegation",
                        "auth-delegation",
                        OBSERVED_AT,
                        "auth",
                        "JWT delegation signature mismatch during federated token validation",
                        EMBEDDINGS.embed("JWT delegation signature mismatch during federated token validation"),
                        AnomalyClass.RARE_ANOMALY
                ),
                new ScenarioProbe(
                        "D. Known Semantic Spike",
                        "sql-connection-refused",
                        "db-connectivity",
                        OBSERVED_AT,
                        "billing",
                        "SQL connection refused by downstream database",
                        EMBEDDINGS.embed("SQL connection refused by downstream database"),
                        AnomalyClass.SURGE_ANOMALY
                ),
                new ScenarioProbe(
                        "E. Critical Compound Anomaly",
                        "cache-sync-corruption",
                        "cache-corruption",
                        OBSERVED_AT,
                        "cache",
                        "Distributed cache synchronization corruption detected",
                        EMBEDDINGS.embed("Distributed cache synchronization corruption detected"),
                        AnomalyClass.CRITICAL_ANOMALY
                ),
                new ScenarioProbe(
                        "F. Stable Operational Noise",
                        "routine-heartbeat",
                        "routine-operations",
                        OBSERVED_AT,
                        "orders",
                        "Order polling heartbeat completed",
                        EMBEDDINGS.embed("Order polling heartbeat completed"),
                        AnomalyClass.NORMAL_BEHAVIOR
                )
        );
    }

    private static void addRecurring(
            List<LogDocument> logs,
            String idPrefix,
            String service,
            String pattern,
            String incidentFamily,
            String scenario,
            String message,
            Instant start,
            int count,
            Duration interval
    ) {
        for (int i = 0; i < count; i++) {
            logs.add(new LogDocument(
                    idPrefix + "-" + i,
                    start.plus(interval.multipliedBy(i)),
                    service,
                    pattern,
                    incidentFamily,
                    scenario,
                    message,
                    EMBEDDINGS.embed(message)
            ));
        }
    }
}
