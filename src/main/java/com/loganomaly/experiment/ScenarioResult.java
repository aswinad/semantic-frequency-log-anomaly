package com.loganomaly.experiment;

import com.loganomaly.core.HybridAnalysisResult;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.opensearch.KnnNeighbor;

import java.util.List;

public record ScenarioResult(
        ScenarioProbe probe,
        SemanticAnalysis semantic,
        TemporalAnalysis temporal,
        HybridAnalysisResult hybrid,
        List<KnnNeighbor> neighbors,
        TemporalAnalysis exactPatternBaseline
) {
}
