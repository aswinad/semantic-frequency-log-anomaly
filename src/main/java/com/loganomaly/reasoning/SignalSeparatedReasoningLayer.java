package com.loganomaly.reasoning;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;

public final class SignalSeparatedReasoningLayer implements ReasoningLayer {
    @Override
    public AnomalyClass classify(SemanticAnalysis semantic, TemporalAnalysis temporal) {
        return HybridAnomalyDetector.classify(semantic.signal(), temporal.signal());
    }
}
