package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.config.OpenStackConfig;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.loghub.OpenStackLogHubDataset;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackTimingNormalizer;
import com.loganomaly.opensearch.KnnNeighbor;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenStackEvaluationWorkflow {
    private static final int MAX_PROBES = 10;
    private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public OpenStackEvaluationWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
        OpenStackConfig config = appConfig.openStack();
        ExperimentConfig experiment = new ExperimentConfig(
                config.shortWindow(),
                config.baselineWindow(),
                appConfig.experiment().topK(),
                appConfig.experiment().noveltyThreshold(),
                appConfig.experiment().similarityThreshold(),
                appConfig.experiment().spikeThreshold()
        );
        OpenStackTimingNormalizer timingNormalizer = timingNormalizer(config);
        Instant observedAt = timingNormalizer.testExperimentRange().end();

        OpenStackLogHubDataset dataset = new OpenStackLogHubDataset(config.loghubDir());
        List<OpenStackLogRecord> probes = selectProbeRecords(dataset.loadAnomalousAbnormalRecords());
        EmbeddingCache cache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        cache.load();

        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromConfig(appConfig, config.indexName())) {
            if (!repository.indexExists()) {
                throw new IllegalStateException("OpenStack index '%s' does not exist. Run DATASET_MODE=openstack DATASET_ACTION=index first."
                        .formatted(config.indexName()));
            }
            OpenSearchLogVectorRepository.VectorIndexValidation validation =
                    repository.validateVectorIndex(embeddingProvider.dimensions());
            if (!validation.valid()) {
                throw new IllegalStateException(
                        "OpenStack index '%s' has invalid mapping: %s. Rerun DATASET_MODE=openstack DATASET_ACTION=index with OPENSTACK_RECREATE_INDEX=true."
                                .formatted(config.indexName(), validation.message())
                );
            }
            System.out.printf("Evaluating existing OpenStack index '%s'%n", config.indexName());
            System.out.printf("Index count: %,d documents, anomaly VM documents: %,d, embedding.type=%s, embedding.dimension=%d%n",
                    repository.countAll(),
                    repository.countIncidentFamily("openstack-anomaly-vm"),
                    validation.embeddingType(),
                    validation.embeddingDimension());
            System.out.printf("Window: short=%s, baseline=%s, observedAt=%s%n%n",
                    experiment.shortWindow(),
                    experiment.baselineWindow(),
                    observedAt);

            printHeader();
            HybridAnomalyDetector detector = new HybridAnomalyDetector(0.5, 0.5);
            List<EvaluationRow> reportRows = new ArrayList<>();
            for (OpenStackLogRecord probe : probes) {
                float[] vector = cache.get(probe.pattern()).orElseGet(() -> embeddingProvider.embed(probe.pattern()));
                List<KnnNeighbor> neighbors = repository.knn(vector, experiment.topK());
                long semanticShort = repository.countSemanticNeighborsBetween(
                        vector,
                        observedAt.minus(experiment.shortWindow()),
                        observedAt,
                        experiment.similarityThreshold()
                );
                long semanticBaseline = repository.countSemanticNeighborsBetween(
                        vector,
                        observedAt.minus(experiment.baselineWindow()),
                        observedAt.minus(experiment.shortWindow()),
                        experiment.similarityThreshold()
                );
                long exactShort = repository.countPatternBetween(
                        probe.pattern(),
                        observedAt.minus(experiment.shortWindow()),
                        observedAt
                );
                long exactBaseline = repository.countPatternBetween(
                        probe.pattern(),
                        observedAt.minus(experiment.baselineWindow()),
                        observedAt.minus(experiment.shortWindow())
                );

                TemporalAnalysis semanticTemporal = new TemporalAnalysis(
                        toIntCount(semanticShort),
                        toIntCount(semanticBaseline),
                        experiment.shortWindow(),
                        experiment.baselineWindow(),
                        experiment.spikeThreshold()
                );
                double maxSimilarity = neighbors.stream()
                        .mapToDouble(KnnNeighbor::cosineSimilarity)
                        .max()
                        .orElse(0.0);
                double boundedSimilarity = boundSimilarity(maxSimilarity);
                SemanticAnalysis semantic = new SemanticAnalysis(
                        toIntCount(semanticBaseline),
                        boundedSimilarity,
                        experiment.noveltyThreshold()
                );
                var hybrid = detector.analyze(semantic, semanticTemporal);
                boolean detectedAsAnomaly = hybrid.anomalyClass() != com.loganomaly.core.AnomalyClass.NORMAL_BEHAVIOR;
                reportRows.add(new EvaluationRow(
                        probe.pattern(),
                        probe.rawMessage(),
                        exactShort,
                        exactBaseline,
                        semanticShort,
                        semanticBaseline,
                        semanticTemporal.spikeRatio(),
                        boundedSimilarity,
                        hybrid.anomalyClass().name(),
                        detectedAsAnomaly
                ));

                System.out.printf(
                        "%-42s %8d %8d %8d %8d %8.2f %-18s %-18s%n",
                        truncate(probe.pattern(), 42),
                        exactShort,
                        exactBaseline,
                        semanticShort,
                        semanticBaseline,
                        semanticTemporal.spikeRatio(),
                        hybrid.anomalyClass(),
                        detectedAsAnomaly ? "DETECTED" : "MISSED"
                );
            }
            writeReportIfEnabled(config, observedAt, reportRows);
        }
    }

    private static List<OpenStackLogRecord> selectProbeRecords(List<OpenStackLogRecord> anomalousRecords) {
        Map<String, OpenStackLogRecord> byPattern = new LinkedHashMap<>();
        for (OpenStackLogRecord record : anomalousRecords) {
            byPattern.putIfAbsent(record.pattern(), record);
            if (byPattern.size() >= MAX_PROBES) {
                break;
            }
        }
        return List.copyOf(byPattern.values());
    }

    private static void printHeader() {
        System.out.printf(
                "%-42s %8s %8s %8s %8s %8s %-18s %-18s%n",
                "Pattern",
                "ExShort",
                "ExBase",
                "SemShort",
                "SemBase",
                "Ratio",
                "Hybrid",
                "Binary"
        );
        System.out.println("-".repeat(132));
    }

    private static OpenStackTimingNormalizer timingNormalizer(OpenStackConfig config) {
        Duration baselinePeriod = config.baselineWindow().minus(config.shortWindow());
        return OpenStackTimingNormalizer.forLogHub(config.experimentAnchor(), baselinePeriod, config.shortWindow());
    }

    private static String truncate(String value, int length) {
        if (value.length() <= length) {
            return value;
        }
        return value.substring(0, Math.max(0, length - 3)) + "...";
    }

    private static int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private static double boundSimilarity(double similarity) {
        if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private void writeReportIfEnabled(OpenStackConfig config, Instant observedAt, List<EvaluationRow> rows) throws IOException {
        if (!appConfig.report().excelEnabled()) {
            return;
        }
        Files.createDirectories(Path.of(appConfig.report().outputDir()));
        String configuredPrefix = appConfig.report().filePrefix();
        String prefix = configuredPrefix.equals("semantic-frequency-paper-test")
                ? "openstack-semantic-frequency-paper-test"
                : configuredPrefix;
        Path reportPath = Path.of(appConfig.report().outputDir(), prefix + "-" + REPORT_TIMESTAMP.format(Instant.now()) + ".xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var summary = workbook.createSheet("Run Summary");
            row(summary.createRow(0), "Dataset", "OpenStack LogHub");
            row(summary.createRow(1), "Index", config.indexName());
            row(summary.createRow(2), "Embedding Provider", embeddingProvider.name());
            row(summary.createRow(3), "Observed At", observedAt.toString());
            row(summary.createRow(4), "Short Window", config.shortWindow().toString());
            row(summary.createRow(5), "Baseline Window", config.baselineWindow().toString());
            row(summary.createRow(6), "Probe Count", Integer.toString(rows.size()));

            var results = workbook.createSheet("Probe Results");
            row(results.createRow(0),
                    "Pattern",
                    "Message",
                    "Exact Short",
                    "Exact Baseline",
                    "Semantic Short",
                    "Semantic Baseline",
                    "Semantic Ratio",
                    "Max Similarity",
                    "Hybrid Class",
                    "Binary Result");
            for (int i = 0; i < rows.size(); i++) {
                EvaluationRow result = rows.get(i);
                row(results.createRow(i + 1),
                        result.pattern(),
                        result.message(),
                        Long.toString(result.exactShort()),
                        Long.toString(result.exactBaseline()),
                        Long.toString(result.semanticShort()),
                        Long.toString(result.semanticBaseline()),
                        Double.toString(result.semanticRatio()),
                        Double.toString(result.maxSimilarity()),
                        result.hybridClass(),
                        result.detected() ? "DETECTED" : "MISSED");
            }
            try (OutputStream outputStream = Files.newOutputStream(reportPath)) {
                workbook.write(outputStream);
            }
        }
        System.out.printf("%nOpenStack evaluation report: %s%n", reportPath.toAbsolutePath());
    }

    private static void row(Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private record EvaluationRow(
            String pattern,
            String message,
            long exactShort,
            long exactBaseline,
            long semanticShort,
            long semanticBaseline,
            double semanticRatio,
            double maxSimilarity,
            String hybridClass,
            boolean detected
    ) {
    }
}
