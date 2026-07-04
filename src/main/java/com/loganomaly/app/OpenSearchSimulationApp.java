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
        if (appConfig.datasetMode() == DatasetMode.BGL && appConfig.datasetAction() == DatasetAction.INDEX) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new BglIndexingWorkflow(appConfig, embeddingProvider).run();
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.BGL && appConfig.datasetAction() == DatasetAction.EVALUATE) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new BglEvaluationWorkflow(appConfig, embeddingProvider).run(false);
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.BGL && appConfig.datasetAction() == DatasetAction.EVALUATE_ABLATION) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new BglEvaluationWorkflow(appConfig, embeddingProvider).run(true);
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.BGL && appConfig.datasetAction() == DatasetAction.ANALYZE_EXISTING) {
            EmbeddingProvider embeddingProvider = EmbeddingProviders.fromConfig(appConfig);
            new BglMetadataAnalysisWorkflow(appConfig, embeddingProvider).run();
            return;
        }
        if (appConfig.datasetMode() == DatasetMode.SYNTHETIC && appConfig.datasetAction() == DatasetAction.ANALYZE_EXISTING) {
            new SyntheticMetadataWorkflow(appConfig).run();
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
            System.out.println("Focus:");
            System.out.println("  Retrieval finds examples; semantic frequency estimates prevalence.");
            System.out.println("  Q. Paraphrased Semantic Surge is the main exact-misses / semantic-detects proof case.");
            System.out.println("  B. Paraphrased Failure Family remains the main semantic-family proof case.");

            if (appConfig.report().excelEnabled()) {
                Path workbookPath = new PaperMetricsWorkbookWriter().write(appConfig, logs, results, runStartedAt);
                System.out.printf("%nExcel paper metrics report: %s%n", workbookPath.toAbsolutePath());
            }
        }
    }

    private static void printHeader() {
        System.out.printf(
                "%-32s %-18s %-18s %-20s %8s%n",
                "Scenario",
                "Exact",
                "Semantic",
                "Hybrid",
                "Pass"
        );
        System.out.println("-".repeat(104));
    }

    private static void printRow(ScenarioResult result) {
        TemporalAnalysis semanticFrequency = result.temporal();
        TemporalAnalysis patternBaseline = result.exactPatternBaseline();
        HybridAnalysisResult hybrid = result.hybrid();
        boolean pass = result.probe().expectedClass() == hybrid.anomalyClass();

        System.out.printf(
                "%-32s %-18s %-18s %-20s %8s%n",
                shortScenarioName(result.probe().name()),
                classifyExact(patternBaseline),
                classifySemantic(semanticFrequency),
                "%s/%s".formatted(result.probe().expectedClass(), hybrid.anomalyClass()),
                pass ? "PASS" : "FAIL"
        );
    }

    private static String classifyExact(TemporalAnalysis analysis) {
        return analysis.signal().name();
    }

    private static String classifySemantic(TemporalAnalysis analysis) {
        return analysis.signal().name();
    }

    private static String shortScenarioName(String name) {
        return switch (name) {
            case "A. Exact Repeated Error" -> "Exact Repeats";
            case "B. Paraphrased Failure Family" -> "Paraphrased Family";
            case "Q. Paraphrased Semantic Surge" -> "Paraphrased Surge";
            case "C. Novel Semantic Event" -> "Novel Event";
            case "D. Known Semantic Spike" -> "Operational Surge";
            case "N. High-Volume Routine Noise" -> "High Volume Normal";
            default -> name;
        };
    }
}
