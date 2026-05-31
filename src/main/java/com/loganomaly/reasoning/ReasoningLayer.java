package com.loganomaly.reasoning;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;

public interface ReasoningLayer {
    AnomalyClass classify(SemanticAnalysis semantic, TemporalAnalysis temporal);
}
