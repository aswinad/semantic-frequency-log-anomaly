package com.loganomaly.experiment;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.opensearch.BglOpenSearchRepository;
import com.loganomaly.opensearch.BglTemplateId;
import com.loganomaly.opensearch.KnnNeighbor;

import java.io.IOException;
import java.util.List;

public final class BglOpenSearchHybridAnalyzer {
    private final BglOpenSearchRepository repository;
    private final HybridAnomalyDetector detector;
    private final ExperimentConfig config;

    public BglOpenSearchHybridAnalyzer(
            BglOpenSearchRepository repository,
            HybridAnomalyDetector detector,
            ExperimentConfig config
    ) {
        this.repository = repository;
        this.detector = detector;
        this.config = config;
    }

    public ScenarioResult analyze(ScenarioProbe probe) throws IOException {
        List<KnnNeighbor> neighbors = repository.knnTemplates(probe.embedding(), config.topK());
        double maxSimilarity = neighbors.stream()
                .mapToDouble(KnnNeighbor::cosineSimilarity)
                .max()
                .orElse(0.0);
        double boundedSimilarity = boundSimilarity(maxSimilarity);

        int shortSemanticCount = Math.toIntExact(repository.countSemanticEventsBetween(
                probe.embedding(),
                probe.observedAt().minus(config.shortWindow()),
                probe.observedAt(),
                config.similarityThreshold()
        ));
        int baselineSemanticCount = Math.toIntExact(repository.countSemanticEventsBetween(
                probe.embedding(),
                probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                probe.observedAt().minus(config.shortWindow()),
                config.similarityThreshold()
        ));

        SemanticAnalysis semantic = new SemanticAnalysis(
                baselineSemanticCount,
                boundedSimilarity,
                config.noveltyThreshold()
        );
        TemporalAnalysis semanticFrequency = new TemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold()
        );
        String templateId = BglTemplateId.fromPattern(probe.pattern());
        TemporalAnalysis exactPatternBaseline = new TemporalAnalysis(
                Math.toIntExact(repository.countEventsForTemplateBetween(
                        templateId,
                        probe.observedAt().minus(config.shortWindow()),
                        probe.observedAt()
                )),
                Math.toIntExact(repository.countEventsForTemplateBetween(
                        templateId,
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

    private static double boundSimilarity(double similarity) {
        if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, similarity));
    }
}
