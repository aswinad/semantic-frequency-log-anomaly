package com.loganomaly.experiment;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.opensearch.KnnNeighbor;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public final class OpenSearchHybridAnalyzer {
    private final OpenSearchLogVectorRepository repository;
    private final HybridAnomalyDetector detector;
    private final ExperimentConfig config;

    public OpenSearchHybridAnalyzer(
            OpenSearchLogVectorRepository repository,
            HybridAnomalyDetector detector,
            ExperimentConfig config
    ) {
        this.repository = repository;
        this.detector = detector;
        this.config = config;
    }

    @Deprecated
    public OpenSearchHybridAnalyzer(
            OpenSearchLogVectorRepository repository,
            HybridAnomalyDetector detector,
            Duration shortWindow,
            Duration longWindow,
            int k,
            int noveltyThreshold,
            double neighborSimilarityThreshold,
            double spikeThreshold
    ) {
        this(
                repository,
                detector,
                new ExperimentConfig(
                        shortWindow,
                        longWindow.minus(shortWindow),
                        k,
                        noveltyThreshold,
                        neighborSimilarityThreshold,
                        spikeThreshold
                )
        );
    }

    public ScenarioResult analyze(ScenarioProbe probe) throws IOException {
        List<KnnNeighbor> neighbors = repository.knn(probe.embedding(), config.topK());
        double maxSimilarity = neighbors.stream()
                .mapToDouble(KnnNeighbor::cosineSimilarity)
                .max()
                .orElse(0.0);

        int shortSemanticCount = Math.toIntExact(repository.countSemanticNeighborsBetween(
                probe.embedding(),
                probe.observedAt().minus(config.shortWindow()),
                probe.observedAt(),
                config.similarityThreshold()
        ));
        int baselineSemanticCount = Math.toIntExact(repository.countSemanticNeighborsBetween(
                probe.embedding(),
                probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                probe.observedAt().minus(config.shortWindow()),
                config.similarityThreshold()
        ));

        SemanticAnalysis semantic = new SemanticAnalysis(
                baselineSemanticCount,
                maxSimilarity,
                config.noveltyThreshold()
        );
        TemporalAnalysis semanticFrequency = new TemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold()
        );
        TemporalAnalysis exactPatternBaseline = new TemporalAnalysis(
                Math.toIntExact(repository.countPatternBetween(
                        probe.pattern(),
                        probe.observedAt().minus(config.shortWindow()),
                        probe.observedAt()
                )),
                Math.toIntExact(repository.countPatternBetween(
                        probe.pattern(),
                        probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                        probe.observedAt().minus(config.shortWindow())
                )),
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold()
        );

        return new ScenarioResult(
                probe,
                semantic,
                semanticFrequency,
                detector.analyze(semantic, semanticFrequency),
                neighbors,
                exactPatternBaseline
        );
    }
}
