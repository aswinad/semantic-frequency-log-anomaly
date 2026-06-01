package com.loganomaly.embedding;

import java.util.Locale;

public final class DeterministicEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 12;

    @Override
    public String name() {
        return "deterministic-synthetic-v1";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "heartbeat", "polling", "completed", "routine", "health check", "metrics export", "websocket")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "database", "jdbc", "sql", "connection pool", "datasource", "transaction rollback")) {
            return vector(1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "cache", "synchronization", "eviction")) {
            return vector(0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "jwt delegation", "federated token", "signature mismatch", "unknown issuer", "unknown trust anchor")) {
            return vector(0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "token expired", "invalid login", "refresh token", "oauth")) {
            return vector(0.00f, 0.00f, 0.05f, 1.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "unknown settlement marker", "unknown reconciliation marker")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.00f);
        }
        if (containsAny(normalized, "payment", "settlement", "card authorization", "reconciliation", "provider")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "disk", "filesystem", "volume", "write latency", "i/o")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "memory", "heap", "gc pause", "garbage collection", "oom")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "network", "packet loss", "latency", "upstream", "tcp")) {
            return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.05f, 0.00f, 0.00f, 0.00f);
        }
        if (containsAny(normalized, "dependency", "retry", "downstream")) {
            return vector(0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.00f, 0.00f);
        }
        return vector(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 1.00f);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static float[] vector(float... values) {
        return values;
    }
}
