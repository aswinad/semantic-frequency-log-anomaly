package com.loganomaly.report;

public enum EvaluationMethod {
    EXACT_PATTERN("Exact Pattern"),
    TOP_K_RETRIEVAL("Top-K Retrieval"),
    SEMANTIC_FREQUENCY("Semantic Frequency"),
    SEMANTIC_TEMPORAL("Semantic Frequency + Temporal"),
    HYBRID_FRAMEWORK("Hybrid Framework");

    private final String displayName;

    EvaluationMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
