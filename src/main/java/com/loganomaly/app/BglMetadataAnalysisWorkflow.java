package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.loghub.BglLogRecord;
import com.loganomaly.report.BinaryGroundTruth;
import com.loganomaly.report.PaperMetadataWorkbookWriter;
import com.loganomaly.report.PublicDatasetEvaluationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class BglMetadataAnalysisWorkflow {
    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public BglMetadataAnalysisWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
        Instant runStartedAt = Instant.now();
        BglConfig config = appConfig.bgl();
        ExperimentConfig experiment = new ExperimentConfig(
                config.shortWindow(),
                config.baselineWindow(),
                appConfig.experiment().topK(),
                appConfig.experiment().noveltyThreshold(),
                appConfig.experiment().similarityThreshold(),
                appConfig.experiment().spikeThreshold()
        );
        BglLogHubDataset dataset = new BglLogHubDataset(config.loghubFile());
        Instant warmupCutoff = dataset.firstTimestamp().plus(config.shortWindow()).plus(config.baselineWindow());
        BglEvaluationWorkflow.EvaluationRange evaluationRange = BglEvaluationWorkflow.resolveEvaluationRange(config, warmupCutoff);

        EmbeddingCache embeddingCache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        embeddingCache.load();

        BglAblationCache ablationCache = new BglAblationCache(config.ablationCache());
        ablationCache.load(false);

        List<BglEvaluationWorkflow.EvaluationCandidate> candidates = BglEvaluationWorkflow.buildCandidates(
                dataset,
                embeddingCache,
                config.evalBucket(),
                warmupCutoff,
                evaluationRange,
                config.candidateMode()
        );
        CandidateFilterStats filterStats = candidateFilterStats(dataset, warmupCutoff, evaluationRange);
        List<PublicDatasetEvaluationResult> results = candidates.stream()
                .map(candidate -> resultFromCache(candidate, ablationCache, experiment, config, evaluationRange))
                .toList();

        System.out.printf("BGL metadata analysis: reconstructed %,d evaluation rows from cache %s%n",
                results.size(),
                config.ablationCache());

        if (appConfig.report().excelEnabled()) {
            Path workbookPath = new PaperMetadataWorkbookWriter().writeBglMetadata(
                    appConfig,
                    results,
                    runStartedAt,
                    evaluationRange,
                    filterStats
            );
            System.out.printf("BGL metadata workbook: %s%n", workbookPath.toAbsolutePath());
        }
    }

    static CandidateFilterStats candidateFilterStats(
            BglLogHubDataset dataset,
            Instant warmupCutoff,
            BglEvaluationWorkflow.EvaluationRange evaluationRange
    ) throws IOException {
        MutableStats stats = new MutableStats();
        dataset.forEachRecord(record -> {
            if (record.timestamp().isBefore(warmupCutoff) || !evaluationRange.contains(record.timestamp())) {
                return;
            }
            stats.totalLogs++;
            if (BglEvaluationWorkflow.isSuspiciousCandidate(record)) {
                stats.candidateLogs++;
            }
            if (BglEvaluationWorkflow.isStrictSuspiciousCandidate(record)) {
                stats.strictCandidateLogs++;
            }
        });
        return new CandidateFilterStats(stats.totalLogs, stats.candidateLogs, stats.strictCandidateLogs);
    }

    private PublicDatasetEvaluationResult resultFromCache(
            BglEvaluationWorkflow.EvaluationCandidate candidate,
            BglAblationCache ablationCache,
            ExperimentConfig experiment,
            BglConfig config,
            BglEvaluationWorkflow.EvaluationRange evaluationRange
    ) {
        String cacheKey = BglAblationCache.cacheKey(
                config.candidateMode(),
                experiment,
                config.minimumHistoricalSupport(),
                config.minimumAlertShortSupport(),
                evaluationRange,
                config.evalBucket(),
                candidate
        );
        BglAblationCache.CachedScenarioPayload payload = ablationCache.get(cacheKey)
                .orElseThrow(() -> new IllegalStateException("Missing cached BGL analysis for candidate: " + candidate.name()));
        ScenarioProbe probe = new ScenarioProbe(
                candidate.name(),
                candidate.pattern(),
                candidate.incidentFamily(),
                candidate.observedAt(),
                candidate.service(),
                candidate.message(),
                candidate.embedding(),
                candidate.groundTruth() == BinaryGroundTruth.ANOMALY ? AnomalyClass.SURGE_ANOMALY : AnomalyClass.NORMAL_BEHAVIOR
        );
        ScenarioResult scenarioResult = BglEvaluationWorkflow.scenarioResultFromCachePayload(
                probe,
                payload,
                experiment,
                config.minimumHistoricalSupport()
        );
        return new PublicDatasetEvaluationResult(
                candidate.name(),
                candidate.groundTruth(),
                candidate.nativeLabel(),
                candidate.eventCount(),
                scenarioResult,
                exactClass(scenarioResult),
                topKClass(scenarioResult, experiment),
                semanticFrequencyClass(scenarioResult),
                semanticTemporalClass(scenarioResult, config.minimumAlertShortSupport()),
                hybridClass(scenarioResult, config.minimumAlertShortSupport())
        );
    }

    public static boolean exactSpike(ScenarioResult result) {
        return result.exactPatternBaseline().signal() == com.loganomaly.core.TemporalSignal.SPIKE;
    }

    public static boolean semanticSpike(ScenarioResult result, int minimumAlertShortSupport) {
        return BglEvaluationWorkflow.looseSemanticPositive(result, minimumAlertShortSupport);
    }

    private static AnomalyClass exactClass(ScenarioResult result) {
        return exactSpike(result) ? AnomalyClass.SURGE_ANOMALY : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass topKClass(ScenarioResult result, ExperimentConfig experiment) {
        return result.semantic().semanticSimilarityScore() < experiment.similarityThreshold()
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass semanticFrequencyClass(ScenarioResult result) {
        return result.semantic().signal() == com.loganomaly.core.SemanticSignal.NOVEL
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass semanticTemporalClass(ScenarioResult result, int minimumAlertShortSupport) {
        return semanticSpike(result, minimumAlertShortSupport)
                ? AnomalyClass.SURGE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass hybridClass(ScenarioResult result, int minimumAlertShortSupport) {
        if (semanticSpike(result, minimumAlertShortSupport)) {
            return result.hybrid().anomalyClass();
        }
        return result.semantic().signal() == com.loganomaly.core.SemanticSignal.NOVEL
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    public record CandidateFilterStats(
            long totalLogs,
            long candidateLogs,
            long strictCandidateLogs
    ) {
    }

    private static final class MutableStats {
        private long totalLogs;
        private long candidateLogs;
        private long strictCandidateLogs;
    }
}
