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

        addDbConnectivity(logs);
        addCacheCorruption(logs);
        addAuthToken(logs);
        addPaymentApi(logs);
        addDiskStorage(logs);
        addMemoryPressure(logs);
        addNetworkLatency(logs);
        addRoutineOperations(logs);

        return logs;
    }

    public static List<ScenarioProbe> probes() {
        return List.of(
                probe("A. Exact Repeated Error", "database-connection-timeout", "db-connectivity", "payments",
                        "Database connection timeout during transaction execution", AnomalyClass.SURGE_ANOMALY),
                probe("B. Paraphrased Failure Family", "jdbc-connection-acquire", "db-connectivity", "orders",
                        "Unable to acquire JDBC connection from pool", AnomalyClass.SURGE_ANOMALY),
                probe("C. Novel Semantic Event", "auth-delegation", "auth-token", "auth",
                        "JWT delegation signature mismatch during federated token validation", AnomalyClass.RARE_ANOMALY),
                probe("D. Known Semantic Spike", "sql-connection-refused", "db-connectivity", "billing",
                        "SQL connection refused by downstream database", AnomalyClass.SURGE_ANOMALY),
                probe("E. Critical Compound Anomaly", "cache-sync-corruption", "cache-corruption", "cache",
                        "Distributed cache synchronization corruption detected", AnomalyClass.CRITICAL_ANOMALY),
                probe("F. Stable Operational Noise", "routine-heartbeat", "routine-operations", "orders",
                        "Order polling heartbeat completed", AnomalyClass.NORMAL_BEHAVIOR),
                probe("G. Hard DB Paraphrase Spike", "datasource-lease-wait", "db-connectivity", "orders",
                        "Application datasource lease wait exceeded before checkout", AnomalyClass.SURGE_ANOMALY),
                probe("H. Payment API Dependency Spike", "payment-provider-timeout", "payment-api", "payments",
                        "Settlement provider timeout while authorizing card transaction", AnomalyClass.SURGE_ANOMALY),
                probe("I. Disk Saturation Known Spike", "disk-volume-nearly-full", "disk-storage", "storage",
                        "Disk volume nearly full on write-ahead log partition", AnomalyClass.SURGE_ANOMALY),
                probe("J. Memory Pressure Gradual Spike", "heap-pressure-warning", "memory-pressure", "checkout",
                        "Heap memory pressure warning after prolonged garbage collection pause", AnomalyClass.SURGE_ANOMALY),
                probe("K. Auth Token Novel Rare Event", "auth-token-unknown-issuer", "auth-token", "auth",
                        "OAuth token rejected because issuer metadata contained unknown trust anchor", AnomalyClass.RARE_ANOMALY),
                probe("L. Network Latency Semantic Spike", "upstream-packet-loss", "network-latency", "gateway",
                        "Upstream packet loss caused elevated network latency", AnomalyClass.SURGE_ANOMALY),
                probe("M. Near-Miss Similar Wording Normal Case", "websocket-connection-closed", "routine-operations", "frontend",
                        "Customer websocket connection closed normally after idle timeout", AnomalyClass.NORMAL_BEHAVIOR),
                probe("N. High-Volume Routine Noise", "metrics-export-completed", "routine-operations", "observability",
                        "Metrics export completed for service telemetry batch", AnomalyClass.NORMAL_BEHAVIOR),
                probe("O. Low-Volume Known Stable Error", "refresh-token-expired", "auth-token", "auth",
                        "Refresh token expired during scheduled session renewal", AnomalyClass.NORMAL_BEHAVIOR),
                probe("P. Novel + Emerging Payment Failure", "payment-reconciliation-marker", "payment-api", "payments",
                        "Payment reconciliation callback rejected unknown settlement marker", AnomalyClass.CRITICAL_ANOMALY)
        );
    }

    private static void addDbConnectivity(List<LogDocument> logs) {
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
    }

    private static void addCacheCorruption(List<LogDocument> logs) {
        addRecurring(logs, "cache-base", "cache", "cache-eviction-retry", "cache-corruption",
                "critical-compound-baseline", "Cache eviction retry threshold exceeded",
                OBSERVED_AT.minus(Duration.ofMinutes(45)), 1, Duration.ofMinutes(1));
        addRecurring(logs, "cache-spike", "cache", "cache-sync-corruption", "cache-corruption",
                "critical-compound-short-window", "Distributed cache synchronization corruption detected",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 12, Duration.ofSeconds(25));
    }

    private static void addAuthToken(List<LogDocument> logs) {
        addRecurring(logs, "auth-expired-base", "auth", "refresh-token-expired", "auth-token",
                "stable-known-auth-baseline", "Refresh token expired during scheduled session renewal",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 22, Duration.ofMinutes(2));
        addRecurring(logs, "auth-expired-short", "auth", "refresh-token-expired", "auth-token",
                "stable-known-auth-short-window", "Refresh token expired during scheduled session renewal",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofMinutes(2));
        addRecurring(logs, "auth-login-base", "auth", "invalid-login-attempt", "auth-token",
                "stable-known-auth-baseline", "Invalid login attempt rejected by policy",
                OBSERVED_AT.minus(Duration.ofMinutes(50)), 5, Duration.ofMinutes(8));
    }

    private static void addPaymentApi(List<LogDocument> logs) {
        addRecurring(logs, "payment-base-provider", "payments", "payment-provider-timeout", "payment-api",
                "payment-api-baseline", "Payment provider timeout while processing authorization",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 2, Duration.ofMinutes(20));
        addRecurring(logs, "payment-base-card", "payments", "card-authorization-refused", "payment-api",
                "payment-api-baseline", "Card authorization gateway refused transaction request",
                OBSERVED_AT.minus(Duration.ofMinutes(50)), 2, Duration.ofMinutes(20));
        addRecurring(logs, "payment-base-reconcile", "settlement", "reconciliation-callback-failed", "payment-api",
                "payment-api-baseline", "Reconciliation callback failed after settlement provider retry",
                OBSERVED_AT.minus(Duration.ofMinutes(45)), 2, Duration.ofMinutes(20));
        addRecurring(logs, "payment-spike-provider", "payments", "payment-provider-timeout", "payment-api",
                "payment-api-short-window", "Settlement provider timeout while authorizing card transaction",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 7, Duration.ofSeconds(35));
        addRecurring(logs, "payment-spike-card", "payments", "card-authorization-refused", "payment-api",
                "payment-api-short-window", "Card authorization gateway refused connection during provider failover",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 6, Duration.ofSeconds(40));
        addRecurring(logs, "payment-spike-reconcile", "settlement", "reconciliation-callback-failed", "payment-api",
                "payment-api-short-window", "Reconciliation callback failed while settlement provider recovered",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 6, Duration.ofSeconds(40));
        addRecurring(logs, "payment-novel-marker", "payments", "payment-reconciliation-marker", "payment-api",
                "payment-api-novel-emerging-short-window", "Payment reconciliation callback rejected unknown settlement marker",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(30));
    }

    private static void addDiskStorage(List<LogDocument> logs) {
        addRecurring(logs, "disk-base-volume", "storage", "disk-volume-nearly-full", "disk-storage",
                "disk-storage-baseline", "Disk volume nearly full on write-ahead log partition",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 4, Duration.ofMinutes(12));
        addRecurring(logs, "disk-base-fs", "storage", "filesystem-write-latency", "disk-storage",
                "disk-storage-baseline", "Filesystem write latency exceeded storage threshold",
                OBSERVED_AT.minus(Duration.ofMinutes(48)), 4, Duration.ofMinutes(12));
        addRecurring(logs, "disk-spike-volume", "storage", "disk-volume-nearly-full", "disk-storage",
                "disk-storage-short-window", "Disk volume nearly full on write-ahead log partition",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(35));
        addRecurring(logs, "disk-spike-io", "storage", "disk-io-queue-saturated", "disk-storage",
                "disk-storage-short-window", "Disk I/O queue saturated while flushing transaction journal",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 8, Duration.ofSeconds(35));
    }

    private static void addMemoryPressure(List<LogDocument> logs) {
        addRecurring(logs, "memory-base-heap", "checkout", "heap-pressure-warning", "memory-pressure",
                "memory-pressure-baseline", "Heap memory pressure warning after prolonged garbage collection pause",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 6, Duration.ofMinutes(9));
        addRecurring(logs, "memory-base-gc", "checkout", "gc-pause-latency", "memory-pressure",
                "memory-pressure-baseline", "GC pause exceeded latency target during checkout flow",
                OBSERVED_AT.minus(Duration.ofMinutes(50)), 4, Duration.ofMinutes(10));
        addRecurring(logs, "memory-gradual-1", "checkout", "heap-pressure-warning", "memory-pressure",
                "memory-pressure-gradual-short-window", "Heap memory pressure warning after prolonged garbage collection pause",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofSeconds(90));
        addRecurring(logs, "memory-gradual-2", "checkout", "gc-pause-latency", "memory-pressure",
                "memory-pressure-gradual-short-window", "Garbage collection pause exceeded latency target",
                OBSERVED_AT.minus(Duration.ofMinutes(3)), 4, Duration.ofSeconds(40));
        addRecurring(logs, "memory-gradual-3", "checkout", "heap-occupancy-high", "memory-pressure",
                "memory-pressure-gradual-short-window", "Heap occupancy above threshold before allocation failure",
                OBSERVED_AT.minus(Duration.ofSeconds(90)), 6, Duration.ofSeconds(15));
    }

    private static void addNetworkLatency(List<LogDocument> logs) {
        addRecurring(logs, "network-base-latency", "gateway", "upstream-latency", "network-latency",
                "network-latency-baseline", "Upstream service latency exceeded network timeout budget",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 4, Duration.ofMinutes(12));
        addRecurring(logs, "network-base-tcp", "gateway", "tcp-retransmit", "network-latency",
                "network-latency-baseline", "TCP retransmit rate increased for upstream dependency",
                OBSERVED_AT.minus(Duration.ofMinutes(48)), 3, Duration.ofMinutes(12));
        addRecurring(logs, "network-spike-packet", "gateway", "upstream-packet-loss", "network-latency",
                "network-latency-short-window", "Upstream packet loss caused elevated network latency",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 7, Duration.ofSeconds(35));
        addRecurring(logs, "network-spike-tcp", "gateway", "tcp-retransmit", "network-latency",
                "network-latency-short-window", "TCP retransmit burst observed for upstream network path",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 7, Duration.ofSeconds(35));
    }

    private static void addRoutineOperations(List<LogDocument> logs) {
        addRecurring(logs, "noise-heartbeat-base", "orders", "routine-heartbeat", "routine-operations",
                "normal-operational-noise", "Order polling heartbeat completed",
                OBSERVED_AT.minus(Duration.ofMinutes(60)), 30, Duration.ofMinutes(2));
        addRecurring(logs, "noise-heartbeat-short", "orders", "routine-heartbeat", "routine-operations",
                "normal-operational-noise", "Order polling heartbeat completed",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofMinutes(2));
        addRecurring(logs, "noise-health", "platform", "health-check-completed", "routine-operations",
                "high-volume-routine-baseline", "Health check completed for service instance",
                OBSERVED_AT.minus(Duration.ofMinutes(60)), 20, Duration.ofMinutes(3));
        addRecurring(logs, "noise-metrics-base", "observability", "metrics-export-completed", "routine-operations",
                "high-volume-routine-baseline", "Metrics export completed for service telemetry batch",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 24, Duration.ofMinutes(2));
        addRecurring(logs, "noise-metrics-short", "observability", "metrics-export-completed", "routine-operations",
                "high-volume-routine-short-window", "Metrics export completed for service telemetry batch",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofMinutes(2));
        addRecurring(logs, "noise-cache-refresh", "cache", "cache-refresh-completed", "routine-operations",
                "high-volume-routine-baseline", "Cache refresh completed for read-through lookup table",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 12, Duration.ofMinutes(4));
        addRecurring(logs, "near-miss-websocket-base", "frontend", "websocket-connection-closed", "routine-operations",
                "near-miss-normal-baseline", "Customer websocket connection closed normally after idle timeout",
                OBSERVED_AT.minus(Duration.ofMinutes(55)), 20, Duration.ofMinutes(2));
        addRecurring(logs, "near-miss-websocket-short", "frontend", "websocket-connection-closed", "routine-operations",
                "near-miss-normal-short-window", "Customer websocket connection closed normally after idle timeout",
                OBSERVED_AT.minus(Duration.ofMinutes(5)), 2, Duration.ofMinutes(2));
    }

    private static ScenarioProbe probe(
            String name,
            String pattern,
            String incidentFamily,
            String service,
            String message,
            AnomalyClass expectedClass
    ) {
        return new ScenarioProbe(
                name,
                pattern,
                incidentFamily,
                OBSERVED_AT,
                service,
                message,
                EMBEDDINGS.embed(message),
                expectedClass
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
                    incidentFamily,
                    scenario,
                    message,
                    EMBEDDINGS.embed(message)
            ));
        }
    }
}
