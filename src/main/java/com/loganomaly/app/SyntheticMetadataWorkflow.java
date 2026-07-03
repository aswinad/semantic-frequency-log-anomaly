package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.report.PaperMetadataWorkbookWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class SyntheticMetadataWorkflow {
    private final AppConfig appConfig;

    public SyntheticMetadataWorkflow(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public void run() throws IOException {
        Instant runStartedAt = Instant.now();
        List<LogDocument> logs = SyntheticLogDataset.historicalLogs();
        List<ScenarioResult> results = SyntheticLogDataset.probes().stream()
                .map(probe -> analyze(probe, logs, appConfig.experiment()))
                .toList();

        System.out.printf("Synthetic metadata analysis: reconstructed %,d scenarios from %,d historical logs%n",
                results.size(),
                logs.size());

        if (appConfig.report().excelEnabled()) {
            Path workbookPath = new PaperMetadataWorkbookWriter().writeSyntheticMetadata(
                    appConfig,
                    results,
                    logs,
                    runStartedAt
            );
            System.out.printf("Synthetic metadata workbook: %s%n", workbookPath.toAbsolutePath());
        }
    }

    public static ScenarioResult analyze(ScenarioProbe probe, List<LogDocument> logs, ExperimentConfig config) {
        List<LogDocument> neighbors = logs.stream()
                .sorted(Comparator.comparingDouble((LogDocument log) -> cosine(probe.embedding(), log.embedding())).reversed())
                .limit(config.topK())
                .toList();
        double maxSimilarity = neighbors.stream()
                .mapToDouble(log -> cosine(probe.embedding(), log.embedding()))
                .max()
                .orElse(0.0);

        int shortSemanticCount = countSemantic(
                probe,
                logs,
                probe.observedAt().minus(config.shortWindow()),
                probe.observedAt(),
                config
        );
        int baselineSemanticCount = countSemantic(
                probe,
                logs,
                probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                probe.observedAt().minus(config.shortWindow()),
                config
        );
        SemanticAnalysis semantic = new SemanticAnalysis(
                baselineSemanticCount,
                maxSimilarity,
                config.noveltyThreshold()
        );
        TemporalAnalysis temporal = new TemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold()
        );
        TemporalAnalysis exact = new TemporalAnalysis(
                countPattern(probe, logs, probe.observedAt().minus(config.shortWindow()), probe.observedAt()),
                countPattern(
                        probe,
                        logs,
                        probe.observedAt().minus(config.shortWindow()).minus(config.baselineWindow()),
                        probe.observedAt().minus(config.shortWindow())
                ),
                config.shortWindow(),
                config.baselineWindow(),
                config.spikeThreshold()
        );

        return new ScenarioResult(
                probe,
                semantic,
                temporal,
                new HybridAnomalyDetector(0.5, 0.5).analyze(semantic, temporal),
                List.of(),
                exact
        );
    }

    private static int countSemantic(
            ScenarioProbe probe,
            List<LogDocument> logs,
            Instant fromInclusive,
            Instant toExclusive,
            ExperimentConfig config
    ) {
        return (int) logs.stream()
                .filter(log -> !log.timestamp().isBefore(fromInclusive) && log.timestamp().isBefore(toExclusive))
                .filter(log -> cosine(probe.embedding(), log.embedding()) >= config.similarityThreshold())
                .count();
    }

    private static int countPattern(
            ScenarioProbe probe,
            List<LogDocument> logs,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        return (int) logs.stream()
                .filter(log -> !log.timestamp().isBefore(fromInclusive) && log.timestamp().isBefore(toExclusive))
                .filter(log -> log.pattern().equals(probe.pattern()))
                .count();
    }

    public static boolean exactSpike(ScenarioResult result) {
        return result.exactPatternBaseline().signal() == com.loganomaly.core.TemporalSignal.SPIKE;
    }

    public static boolean semanticSpike(ScenarioResult result) {
        return result.temporal().signal() == com.loganomaly.core.TemporalSignal.SPIKE;
    }

    static AnomalyClass exactClass(ScenarioResult result) {
        return exactSpike(result) ? AnomalyClass.SURGE_ANOMALY : AnomalyClass.NORMAL_BEHAVIOR;
    }

    static AnomalyClass semanticClass(ScenarioResult result) {
        return semanticSpike(result) ? AnomalyClass.SURGE_ANOMALY : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftMagnitude += left[i] * left[i];
            rightMagnitude += right[i] * right[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
