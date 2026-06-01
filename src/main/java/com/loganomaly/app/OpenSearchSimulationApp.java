package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.DatasetAction;
import com.loganomaly.config.DatasetMode;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.HybridAnalysisResult;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.embedding.EmbeddingProviders;
import com.loganomaly.experiment.OpenSearchHybridAnalyzer;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;
import com.loganomaly.report.PaperMetricsWorkbookWriter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OpenSearchSimulationApp {
    private OpenSearchSimulationApp() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig appConfig = AppConfig.load();
        dispatch(appConfig);
    }

    public static void dispatch(AppConfig appConfig) throws Exception {
        if (appConfig.datasetMode() == DatasetMode.SYNTHETIC && appConfig.datasetAction() == DatasetAction.EVALUATE) {
            runSyntheticEvaluation(appConfig);
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.OPENSTACK && appConfig.datasetAction() == DatasetAction.INDEX) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new OpenStackIndexingWorkflow(appConfig, embeddingProvider).run();
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.OPENSTACK && appConfig.datasetAction() == DatasetAction.EVALUATE) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new OpenStackEvaluationWorkflow(appConfig, embeddingProvider).run();
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported DATASET_MODE/DATASET_ACTION combination: %s/%s"
                        .formatted(appConfig.datasetMode(), appConfig.datasetAction())
        );
    }

    private static void runSyntheticEvaluation(AppConfig appConfig) throws Exception {
        Instant runStartedAt = Instant.now();
        ExperimentConfig config = appConfig.experiment();

        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromConfig(appConfig)) {
            List<LogDocument> logs = SyntheticLogDataset.historicalLogs();
            repository.recreateIndex(SyntheticLogDataset.dimensions());
            repository.indexAll(logs);
            repository.refresh();

            OpenSearchHybridAnalyzer analyzer = new OpenSearchHybridAnalyzer(
                    repository,
                    new HybridAnomalyDetector(0.5, 0.5),
                    config
            );

            System.out.printf("Seeded %d synthetic historical logs into OpenSearch index '%s'%n", logs.size(), appConfig.openSearchIndex());
            System.out.printf(
                    "Config: embeddingProvider=%s, topK=%d, similarityThreshold=%.2f, shortWindow=%s, baselineWindow=%s, spikeThreshold=%.2f%n%n",
                    appConfig.embeddingProviderName(),
                    config.topK(),
                    config.similarityThreshold(),
                    config.shortWindow(),
                    config.baselineWindow(),
                    config.spikeThreshold()
            );

            printHeader();
            List<ScenarioResult> results = new ArrayList<>();
            for (ScenarioProbe probe : SyntheticLogDataset.probes()) {
                ScenarioResult result = analyzer.analyze(probe);
                results.add(result);
                printRow(result);
            }

            System.out.println();
            System.out.println("Legend:");
            System.out.println("  Pattern = exact normalized pattern count. It is narrow frequency.");
            System.out.println("  Top-K = representative examples. It is not frequency.");
            System.out.println("  Semantic = threshold count across all similar logs in the time window. It is semantic prevalence.");
            System.out.println("  Hybrid = deterministic classification from semantic familiarity + semantic-frequency deviation.");

            if (appConfig.report().excelEnabled()) {
                Path workbookPath = new PaperMetricsWorkbookWriter().write(appConfig, logs, results, runStartedAt);
                System.out.printf("%nExcel paper metrics report: %s%n", workbookPath.toAbsolutePath());
            }
        }
    }

    private static void printHeader() {
        System.out.printf(
                "%-32s %-24s %-18s %-30s %-24s %8s%n",
                "Scenario",
                "Exact Pattern",
                "Top-K Only",
                "Semantic Frequency",
                "Hybrid",
                "Pass"
        );
        System.out.println("-".repeat(146));
    }

    private static void printRow(ScenarioResult result) {
        TemporalAnalysis semanticFrequency = result.temporal();
        TemporalAnalysis patternBaseline = result.exactPatternBaseline();
        HybridAnalysisResult hybrid = result.hybrid();
        boolean pass = result.probe().expectedClass() == hybrid.anomalyClass();

        System.out.printf(
                "%-32s %-24s %-18s %-30s %-24s %8s%n",
                result.probe().name(),
                summarizeTemporal(patternBaseline),
                "%d examples".formatted(result.neighbors().size()),
                summarizeTemporal(semanticFrequency),
                "%s/%s".formatted(result.probe().expectedClass(), hybrid.anomalyClass()),
                pass ? "PASS" : "FAIL"
        );

        System.out.printf("  Message: %s%n", result.probe().message());
        System.out.printf("  Top-K examples: %s%n", summarizeExamples(result));
        System.out.printf("  Semantic familiarity: baselineCount=%d, maxSimilarity=%.3f, signal=%s%n",
                result.semantic().semanticCount(),
                result.semantic().semanticSimilarityScore(),
                hybrid.semanticSignal());
        System.out.printf("  Hybrid score: %.3f%n%n", hybrid.hybridAnomalyScore());
    }

    private static String summarizeTemporal(TemporalAnalysis analysis) {
        return "%d/%d exp=%.1f ratio=%.1f %s".formatted(
                analysis.shortCount(),
                analysis.longCount(),
                analysis.expectedShortTermCount(),
                analysis.spikeRatio(),
                analysis.signal()
        );
    }

    private static String summarizeExamples(ScenarioResult result) {
        return result.neighbors().stream()
                .limit(3)
                .map(neighbor -> "%s (%.2f)".formatted(neighbor.message(), neighbor.cosineSimilarity()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }
}
