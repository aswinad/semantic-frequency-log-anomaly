package com.loganomaly.core;

public final class BaselineClassifier {
    public AnomalyClass semanticOnly(SemanticAnalysis semantic) {
        return semantic.signal() == SemanticSignal.NOVEL
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    public AnomalyClass statisticalOnly(TemporalAnalysis temporal) {
        return temporal.signal() == TemporalSignal.SPIKE
                ? AnomalyClass.SURGE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }
}
