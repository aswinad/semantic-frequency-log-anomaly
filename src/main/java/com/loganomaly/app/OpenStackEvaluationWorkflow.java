package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.config.OpenStackConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.embedding.OpenAIEmbeddingProvider;
import com.loganomaly.experiment.OpenSearchHybridAnalyzer;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.loghub.OpenStackLogHubDataset;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackSourceRole;
import com.loganomaly.loghub.OpenStackTimingNormalizer;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;
import com.loganomaly.report.BinaryGroundTruth;
import com.loganomaly.report.EvaluationMethod;
import com.loganomaly.report.PublicDatasetEvaluation;
import com.loganomaly.report.PublicDatasetEvaluationResult;
import com.loganomaly.report.PublicDatasetWorkbookWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenStackEvaluationWorkflow {
    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public OpenStackEvaluationWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
        OpenStackConfig config = appConfig.openStack();
        Instant embeddingsStartedAt = Instant.now();
        ExperimentConfig experiment = new ExperimentConfig(
                config.shortWindow(),
                config.baselineWindow(),
                appConfig.experiment().topK(),
                appConfig.experiment().noveltyThreshold(),
                appConfig.experiment().similarityThreshold(),
                appConfig.experiment().spikeThreshold()
        );
        OpenStackTimingNormalizer timingNormalizer = timingNormalizer(config);

        OpenStackLogHubDataset dataset = new OpenStackLogHubDataset(config.loghubDir());
        List<OpenStackLogRecord> records = dataset.loadAll();
        EmbeddingCache cache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        cache.load();
        ensureCandidateEmbeddings(records, cache, embeddingProvider, config.batchSize());
        Duration embeddingDuration = Duration.between(embeddingsStartedAt, Instant.now());
        Instant candidateSelectionStartedAt = Instant.now();
        List<EvaluationCandidate> candidates = buildCandidates(records, cache, timingNormalizer);
        Duration candidateSelectionDuration = Duration.between(candidateSelectionStartedAt, Instant.now());

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
            System.out.printf("Window: short=%s, baseline=%s, abnormalWindowEnd=%s%n",
                    experiment.shortWindow(),
                    experiment.baselineWindow(),
                    timingNormalizer.testExperimentRange().end());
            System.out.printf("Eligible evaluation rows: %,d (weighted positives=%d, weighted negatives=%d)%n%n",
                    candidates.size(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.ANOMALY).mapToLong(EvaluationCandidate::eventCount).sum(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.NORMAL).mapToLong(EvaluationCandidate::eventCount).sum());

            OpenSearchHybridAnalyzer analyzer = new OpenSearchHybridAnalyzer(
                    repository,
                    new HybridAnomalyDetector(0.5, 0.5),
                    experiment
            );
            List<PublicDatasetEvaluationResult> results = new ArrayList<>(candidates.size());

            printHeader();
            Instant analysisStartedAt = Instant.now();
            for (EvaluationCandidate candidate : candidates) {
                ScenarioProbe probe = new ScenarioProbe(
                        candidate.name(),
                        candidate.pattern(),
                        candidate.incidentFamily(),
                        candidate.observedAt(),
                        candidate.service(),
                        candidate.message(),
                        candidate.embedding(),
                        candidate.groundTruth() == BinaryGroundTruth.ANOMALY
                                ? AnomalyClass.SURGE_ANOMALY
                                : AnomalyClass.NORMAL_BEHAVIOR
                );
                ScenarioResult scenarioResult = analyzer.analyze(probe);
                PublicDatasetEvaluationResult result = new PublicDatasetEvaluationResult(
                        candidate.name(),
                        candidate.groundTruth(),
                        candidate.nativeLabel(),
                        candidate.eventCount(),
                        scenarioResult,
                        classify(scenarioResult, EvaluationMethod.EXACT_PATTERN, experiment),
                        classify(scenarioResult, EvaluationMethod.TOP_K_RETRIEVAL, experiment),
                        classify(scenarioResult, EvaluationMethod.SEMANTIC_FREQUENCY, experiment),
                        classify(scenarioResult, EvaluationMethod.SEMANTIC_TEMPORAL, experiment),
                        scenarioResult.hybrid().anomalyClass()
                );
                results.add(result);

                System.out.printf(
                        "%-34s %-8s %8d %8d %8d %8d %8.2f %-18s %-12s%n",
                        truncate(candidate.pattern(), 34),
                        candidate.groundTruth().name(),
                        candidate.eventCount(),
                        scenarioResult.exactPatternBaseline().shortCount(),
                        scenarioResult.exactPatternBaseline().longCount(),
                        scenarioResult.temporal().shortCount(),
                        scenarioResult.temporal().spikeRatio(),
                        result.predictedClass(EvaluationMethod.HYBRID_FRAMEWORK),
                        PublicDatasetEvaluation.isPositiveForResult(result, EvaluationMethod.HYBRID_FRAMEWORK) ? "ANOMALY" : "NORMAL"
                );
            }
            Duration semanticAnalysisDuration = Duration.between(analysisStartedAt, Instant.now());

            if (appConfig.report().excelEnabled()) {
                PublicDatasetWorkbookWriter.OpenStackCaseStudySummary summary =
                        new PublicDatasetWorkbookWriter.OpenStackCaseStudySummary(
                                records.size(),
                                candidates.size(),
                                candidates.stream().map(EvaluationCandidate::pattern).distinct().count(),
                                results.stream()
                                        .filter(result -> PublicDatasetEvaluation.isSpike(result.predictedClass(EvaluationMethod.HYBRID_FRAMEWORK)))
                                        .count(),
                                embeddingDuration.toString(),
                                "existing index reused",
                                candidateSelectionDuration.toString(),
                                semanticAnalysisDuration.toString()
                        );
                Path workbookPath = new PublicDatasetWorkbookWriter().writeOpenStack(appConfig, results, Instant.now(), summary);
                System.out.printf("%nOpenStack paper metrics report: %s%n", workbookPath.toAbsolutePath());
            }
        }
    }

    static void ensureCandidateEmbeddings(
            List<OpenStackLogRecord> records,
            EmbeddingCache cache,
            EmbeddingProvider embeddingProvider,
            int batchSize
    ) throws IOException {
        Set<String> missingTemplates = new LinkedHashSet<>();
        for (OpenStackLogRecord record : records) {
            if (!isEligibleBinaryCandidate(record)) {
                continue;
            }
            if (cache.get(record.pattern()).isEmpty()) {
                missingTemplates.add(record.pattern());
            }
        }
        System.out.printf("OpenStack evaluation cache missing %,d candidate templates%n", missingTemplates.size());
        if (missingTemplates.isEmpty()) {
            return;
        }

        List<String> templates = new ArrayList<>(missingTemplates);
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int from = 0; from < templates.size(); from += effectiveBatchSize) {
            int to = Math.min(templates.size(), from + effectiveBatchSize);
            List<String> batch = templates.subList(from, to);
            List<float[]> embeddings = embedBatch(embeddingProvider, batch);
            Map<String, float[]> newEmbeddings = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                newEmbeddings.put(batch.get(i), embeddings.get(i));
            }
            cache.putAll(newEmbeddings);
            System.out.printf("Cached evaluation embeddings %,d/%,d%n", to, templates.size());
        }
    }

    private static List<EvaluationCandidate> buildCandidates(
            List<OpenStackLogRecord> records,
            EmbeddingCache cache,
            OpenStackTimingNormalizer timingNormalizer
    ) {
        Map<CandidateKey, MutableCandidate> grouped = new LinkedHashMap<>();
        for (OpenStackLogRecord record : records) {
            if (!isEligibleBinaryCandidate(record)) {
                continue;
            }
            Instant observedAt = timingNormalizer.normalize(record.role(), record.originalTimestamp());
            BinaryGroundTruth groundTruth = "openstack-anomaly-vm".equals(record.incidentFamily())
                    ? BinaryGroundTruth.ANOMALY
                    : BinaryGroundTruth.NORMAL;
            CandidateKey key = new CandidateKey(groundTruth, record.service(), record.pattern());
            MutableCandidate candidate = grouped.computeIfAbsent(key, ignored -> new MutableCandidate(
                    nameFor(groundTruth, record.service(), record.pattern()),
                    record.pattern(),
                    record.service(),
                    groundTruth == BinaryGroundTruth.ANOMALY ? "openstack-anomaly-vm" : "openstack-normal",
                    groundTruth == BinaryGroundTruth.ANOMALY ? "openstack-anomaly-vm" : "openstack-normal",
                    record.rawMessage(),
                    cache.get(record.pattern()).orElseThrow(() ->
                            new IllegalStateException("Missing cached embedding for " + record.pattern())),
                    groundTruth,
                    observedAt
            ));
            candidate.increment();
            candidate.observeAt(observedAt);
        }
        return grouped.values().stream()
                .map(MutableCandidate::toImmutable)
                .toList();
    }

    private static boolean isEligibleBinaryCandidate(OpenStackLogRecord record) {
        return record.role() != OpenStackSourceRole.ABNORMAL_TEST
                || "openstack-anomaly-vm".equals(record.incidentFamily());
    }

    private static List<float[]> embedBatch(EmbeddingProvider embeddingProvider, List<String> batch) {
        if (embeddingProvider instanceof OpenAIEmbeddingProvider openAIEmbeddingProvider) {
            return openAIEmbeddingProvider.embedBatch(batch);
        }
        return batch.stream().map(embeddingProvider::embed).toList();
    }

    private static AnomalyClass classify(ScenarioResult result, EvaluationMethod method, ExperimentConfig config) {
        return switch (method) {
            case EXACT_PATTERN -> result.exactPatternBaseline().signal() == com.loganomaly.core.TemporalSignal.SPIKE
                    ? AnomalyClass.SURGE_ANOMALY
                    : AnomalyClass.NORMAL_BEHAVIOR;
            case TOP_K_RETRIEVAL -> result.semantic().semanticSimilarityScore() < config.similarityThreshold()
                    ? AnomalyClass.RARE_ANOMALY
                    : AnomalyClass.NORMAL_BEHAVIOR;
            case SEMANTIC_FREQUENCY -> result.semantic().signal() == com.loganomaly.core.SemanticSignal.NOVEL
                    ? AnomalyClass.RARE_ANOMALY
                    : AnomalyClass.NORMAL_BEHAVIOR;
            case SEMANTIC_TEMPORAL -> result.temporal().signal() == com.loganomaly.core.TemporalSignal.SPIKE
                    ? AnomalyClass.SURGE_ANOMALY
                    : AnomalyClass.NORMAL_BEHAVIOR;
            case HYBRID_FRAMEWORK -> result.hybrid().anomalyClass();
        };
    }

    private static String nameFor(BinaryGroundTruth groundTruth, String service, String pattern) {
        return "%s | %s | %s".formatted(groundTruth.name(), service, truncate(pattern, 40));
    }

    private static void printHeader() {
        System.out.printf(
                "%-34s %-8s %8s %8s %8s %8s %8s %-18s %-12s%n",
                "Pattern",
                "Truth",
                "Weight",
                "ExShort",
                "ExBase",
                "SemShort",
                "Ratio",
                "Hybrid",
                "Binary"
        );
        System.out.println("-".repeat(126));
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

    private record EvaluationCandidate(
            String name,
            String pattern,
            String service,
            String incidentFamily,
            String nativeLabel,
            String message,
            float[] embedding,
            BinaryGroundTruth groundTruth,
            long eventCount,
            Instant observedAt
    ) {
    }

    private record CandidateKey(BinaryGroundTruth groundTruth, String service, String pattern) {
    }

    private static final class MutableCandidate {
        private final String name;
        private final String pattern;
        private final String service;
        private final String incidentFamily;
        private final String nativeLabel;
        private final String message;
        private final float[] embedding;
        private final BinaryGroundTruth groundTruth;
        private Instant observedAt;
        private long eventCount;

        private MutableCandidate(
                String name,
                String pattern,
                String service,
                String incidentFamily,
                String nativeLabel,
                String message,
                float[] embedding,
                BinaryGroundTruth groundTruth,
                Instant observedAt
        ) {
            this.name = name;
            this.pattern = pattern;
            this.service = service;
            this.incidentFamily = incidentFamily;
            this.nativeLabel = nativeLabel;
            this.message = message;
            this.embedding = embedding;
            this.groundTruth = groundTruth;
            this.observedAt = observedAt;
        }

        private void increment() {
            eventCount++;
        }

        private void observeAt(Instant candidateObservedAt) {
            if (candidateObservedAt.isAfter(observedAt)) {
                observedAt = candidateObservedAt;
            }
        }

        private EvaluationCandidate toImmutable() {
            return new EvaluationCandidate(name, pattern, service, incidentFamily, nativeLabel, message, embedding, groundTruth, eventCount, observedAt);
        }
    }
}
