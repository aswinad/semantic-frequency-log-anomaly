package com.loganomaly.reasoning;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;

public final class ConflatingReasoningLayer implements ReasoningLayer {
    @Override
    public AnomalyClass classify(SemanticAnalysis semantic, TemporalAnalysis temporal) {
        TemporalAnalysis incorrectTemporalView = new TemporalAnalysis(
                semantic.semanticCount(),
                temporal.longCount(),
                temporal.shortWindow(),
                temporal.longWindow(),
                temporal.spikeThreshold()
        );
        return HybridAnomalyDetector.classify(semantic.signal(), incorrectTemporalView.signal());
    }
}
