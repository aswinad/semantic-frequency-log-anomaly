package com.loganomaly.embedding;

public interface EmbeddingProvider {
    String name();

    int dimensions();

    float[] embed(String text);
}
