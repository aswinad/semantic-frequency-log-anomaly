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
    private final int minimumHistoricalSupport;
    private final String semanticLookupContextLabel;

    public BglOpenSearchHybridAnalyzer(
            BglOpenSearchRepository repository,
            HybridAnomalyDetector detector,
            ExperimentConfig config,
            int minimumHistoricalSupport
    ) {
        this(repository, detector, config, minimumHistoricalSupport, "bgl-semantic");
    }

    public BglOpenSearchHybridAnalyzer(
            BglOpenSearchRepository repository,
            HybridAnomalyDetector detector,
            ExperimentConfig config,
            int minimumHistoricalSupport,
            String semanticLookupContextLabel
    ) {
        this.repository = repository;
        this.detector = detector;
        this.config = config;
        this.minimumHistoricalSupport = minimumHistoricalSupport;
        this.semanticLookupContextLabel = semanticLookupContextLabel;
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
                config.similarityThreshold(),
                semanticLookupContextLabel
        ));
        int baselineSemanticCount = Math.toIntExact(repository.countSemanticEventsBetween(
                probe.embedding(),
                probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                probe.observedAt().minus(config.shortWindow()),
                config.similarityThreshold(),
                semanticLookupContextLabel
        ));

        SemanticAnalysis semantic = new SemanticAnalysis(
                baselineSemanticCount,
                boundedSimilarity,
                config.noveltyThreshold()
        );
        TemporalAnalysis semanticFrequency = historyAwareTemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                config,
                minimumHistoricalSupport
        );
        String templateId = BglTemplateId.fromPattern(probe.pattern());
        TemporalAnalysis exactPatternBaseline = historyAwareTemporalAnalysis(
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
                config,
                minimumHistoricalSupport
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

    static TemporalAnalysis historyAwareTemporalAnalysis(
            int shortSemanticCount,
            int baselineSemanticCount,
            ExperimentConfig config,
            int minimumHistoricalSupport
    ) {
        return new TemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold(),
                minimumHistoricalSupport,
                true
        );
    }
}
