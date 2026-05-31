package com.loganomaly;

import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEmbeddingProviderTest {
    private final EmbeddingProvider embeddings = new DeterministicEmbeddingProvider();

    @Test
    void mapsParaphrasedDatabaseFailuresIntoSameSemanticNeighborhood() {
        float[] timeout = embeddings.embed("Database connection timeout during transaction execution");
        float[] jdbc = embeddings.embed("Unable to acquire JDBC connection from pool");
        float[] sql = embeddings.embed("SQL connection refused by downstream database");
        float[] auth = embeddings.embed("JWT delegation signature mismatch during federated token validation");

        assertTrue(cosine(timeout, jdbc) >= 0.99);
        assertTrue(cosine(timeout, sql) >= 0.99);
        assertTrue(cosine(timeout, auth) < 0.20);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftMagnitude += left[i] * left[i];
            rightMagnitude += right[i] * right[i];
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
