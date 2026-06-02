package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.config.BglConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.embedding.OpenAIEmbeddingProvider;
import com.loganomaly.experiment.BglOpenSearchHybridAnalyzer;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.loghub.BglLogRecord;
import com.loganomaly.opensearch.BglOpenSearchRepository;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BglEvaluationWorkflow {
    private static final List<String> SUSPICIOUS_PHRASES = List.of(
            "failed",
            "mount failed",
            "error reading",
            "error receiving",
            "connection reset",
            "timed",
            "unavailable",
            "cannot allocate",
            "resource busy",
            "panic",
            "stopping execution",
            "kernel terminated",
            "terminated",
            "bad message header",
            "invalid",
            "tlb error interrupt",
            "storage interrupt"
    );

    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public BglEvaluationWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
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
        Instant firstTimestamp = dataset.firstTimestamp();
        Instant warmupCutoff = firstTimestamp.plus(config.shortWindow()).plus(config.baselineWindow());
        EvaluationRange evaluationRange = resolveEvaluationRange(config, warmupCutoff);

        EmbeddingCache cache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        cache.load();
        ensureCandidateEmbeddings(dataset, cache, embeddingProvider, config.batchSize(), warmupCutoff, evaluationRange, config.candidateMode());
        List<EvaluationCandidate> candidates = buildCandidates(dataset, cache, config.evalBucket(), warmupCutoff, evaluationRange, config.candidateMode());

        try (BglOpenSearchRepository repository = BglOpenSearchRepository.fromConfig(appConfig, config.indexName())) {
            if (!repository.indexesExist()) {
                throw new IllegalStateException("BGL indexes '%s' and '%s' do not both exist. Run DATASET_MODE=bgl DATASET_ACTION=index first."
                        .formatted(repository.templateIndexName(), repository.eventIndexName()));
            }
            BglOpenSearchRepository.BglIndexValidation templateValidation =
                    repository.validateTemplateIndex(embeddingProvider.dimensions());
            BglOpenSearchRepository.BglIndexValidation eventValidation = repository.validateEventIndex();
            if (!templateValidation.valid() || !eventValidation.valid()) {
                throw new IllegalStateException(
                        "BGL indexes derived from '%s' have invalid mapping: %s. Rerun DATASET_MODE=bgl DATASET_ACTION=index with BGL_RECREATE_INDEX=true."
                                .formatted(config.indexName(), !templateValidation.valid() ? templateValidation.message() : eventValidation.message())
                );
            }

            long totalTemplates = repository.countAllTemplates();
            long totalEvents = repository.countAllEvents();
            long anomalyEvents = totalEvents - repository.countEventsByNativeLabel("-");
            System.out.printf("Evaluating existing BGL indexes '%s' and '%s'%n", repository.templateIndexName(), repository.eventIndexName());
            System.out.printf("Index count: %,d templates, %,d events, anomaly events: %,d%n",
                    totalTemplates,
                    totalEvents,
                    anomalyEvents);
            System.out.printf("Window: short=%s, baseline=%s, evalBucket=%s%n",
                    experiment.shortWindow(),
                    experiment.baselineWindow(),
                    config.evalBucket());
            System.out.printf("Candidate mode: %s%n", config.candidateMode());
            System.out.printf("Evaluation slice: start=%s, end=%s, durationDays=%d, mode=%s%n",
                    evaluationRange.start(),
                    evaluationRange.end(),
                    config.evalDuration().toDays(),
                    config.evalRangeMode());
            System.out.printf("Eligible evaluation rows: %,d (weighted positives=%d, weighted negatives=%d)%n%n",
                    candidates.size(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.ANOMALY).mapToLong(EvaluationCandidate::eventCount).sum(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.NORMAL).mapToLong(EvaluationCandidate::eventCount).sum());

            BglOpenSearchHybridAnalyzer analyzer = new BglOpenSearchHybridAnalyzer(
                    repository,
                    new HybridAnomalyDetector(0.5, 0.5),
                    experiment
            );
            List<PublicDatasetEvaluationResult> results = new ArrayList<>(candidates.size());

            printHeader();
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
                        classify(scenarioResult, EvaluationMethod.SEMANTIC_TEMPORAL, experiment)
                );
                results.add(result);

                System.out.printf(
                        "%-34s %-10s %8d %8d %8d %8d %8.2f %-18s %-12s%n",
                        truncate(candidate.pattern(), 34),
                        candidate.nativeLabel(),
                        candidate.eventCount(),
                        scenarioResult.exactPatternBaseline().shortCount(),
                        scenarioResult.exactPatternBaseline().longCount(),
                        scenarioResult.temporal().shortCount(),
                        scenarioResult.temporal().spikeRatio(),
                        scenarioResult.hybrid().anomalyClass(),
                        PublicDatasetEvaluation.isAnomaly(scenarioResult.hybrid().anomalyClass()) ? "ANOMALY" : "NORMAL"
                );
            }

            if (appConfig.report().excelEnabled()) {
                Path workbookPath = new PublicDatasetWorkbookWriter().writeBgl(appConfig, results, Instant.now(), evaluationRange);
                System.out.printf("%nBGL paper metrics report: %s%n", workbookPath.toAbsolutePath());
            }
        }
    }

    static void ensureCandidateEmbeddings(
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            EmbeddingProvider embeddingProvider,
            int batchSize,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglCandidateMode candidateMode
    ) throws IOException {
        Set<String> missingTemplates = new LinkedHashSet<>();
        dataset.forEachRecord(record -> {
            if (!isEligibleCandidate(record, warmupCutoff, evaluationRange, candidateMode)) {
                return;
            }
            if (cache.get(record.pattern()).isEmpty()) {
                missingTemplates.add(record.pattern());
            }
        });
        System.out.printf("BGL evaluation cache missing %,d candidate templates%n", missingTemplates.size());
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

    static List<EvaluationCandidate> buildCandidates(
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            Duration evalBucket,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglCandidateMode candidateMode
    ) throws IOException {
        Map<CandidateKey, MutableCandidate> grouped = new LinkedHashMap<>();
        dataset.forEachRecord(record -> {
            Instant observedAt = bucketEnd(record.timestamp(), evalBucket);
            if (!isEligibleCandidate(record, warmupCutoff, evaluationRange, candidateMode) || !evaluationRange.contains(observedAt)) {
                return;
            }
            BinaryGroundTruth groundTruth = record.anomaly() ? BinaryGroundTruth.ANOMALY : BinaryGroundTruth.NORMAL;
            CandidateKey key = new CandidateKey(groundTruth, record.rawLabel(), record.service(), record.pattern(), observedAt);
            MutableCandidate candidate = grouped.computeIfAbsent(key, ignored -> new MutableCandidate(
                    nameFor(groundTruth, record.rawLabel(), record.service(), record.pattern()),
                    record.pattern(),
                    record.service(),
                    groundTruth == BinaryGroundTruth.ANOMALY ? "bgl-anomaly" : "bgl-normal",
                    record.rawLabel(),
                    record.rawMessage(),
                    cache.get(record.pattern()).orElseThrow(() ->
                            new IllegalStateException("Missing cached embedding for " + record.pattern())),
                    groundTruth,
                    observedAt
            ));
            candidate.increment();
        });
        return grouped.values().stream().map(MutableCandidate::toImmutable).toList();
    }

    private static boolean isEligibleCandidate(
            BglLogRecord record,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglCandidateMode candidateMode
    ) {
        return !record.timestamp().isBefore(warmupCutoff)
                && evaluationRange.contains(record.timestamp())
                && matchesCandidateMode(record, candidateMode);
    }

    private static boolean matchesCandidateMode(BglLogRecord record, BglCandidateMode candidateMode) {
        return switch (candidateMode) {
            case ALL -> true;
            case FILTERED -> isSuspiciousCandidate(record);
        };
    }

    private static EvaluationRange resolveEvaluationRange(BglConfig config, Instant warmupCutoff) {
        Instant start = config.evalStart().orElse(warmupCutoff);
        if (start.isBefore(warmupCutoff)) {
            start = warmupCutoff;
        }
        return new EvaluationRange(start, start.plus(config.evalDuration()));
    }

    static boolean isSuspiciousCandidate(BglLogRecord record) {
        String pattern = record.pattern();
        for (String phrase : SUSPICIOUS_PHRASES) {
            if (pattern.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static List<float[]> embedBatch(EmbeddingProvider embeddingProvider, List<String> batch) {
        if (embeddingProvider instanceof OpenAIEmbeddingProvider openAIEmbeddingProvider) {
            return openAIEmbeddingProvider.embedBatch(batch);
        }
        return batch.stream().map(embeddingProvider::embed).toList();
    }

    private static Instant bucketEnd(Instant timestamp, Duration bucket) {
        long bucketMillis = bucket.toMillis();
        long timestampMillis = timestamp.toEpochMilli();
        long remainder = Math.floorMod(timestampMillis, bucketMillis);
        long bucketEndMillis = remainder == 0 ? timestampMillis : timestampMillis + (bucketMillis - remainder);
        return Instant.ofEpochMilli(bucketEndMillis);
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

    private static String nameFor(BinaryGroundTruth groundTruth, String nativeLabel, String service, String pattern) {
        return "%s | %s | %s | %s".formatted(groundTruth.name(), nativeLabel, service, truncate(pattern, 32));
    }

    private static void printHeader() {
        System.out.printf(
                "%-34s %-10s %8s %8s %8s %8s %8s %-18s %-12s%n",
                "Pattern",
                "Label",
                "Weight",
                "ExShort",
                "ExBase",
                "SemShort",
                "Ratio",
                "Hybrid",
                "Binary"
        );
        System.out.println("-".repeat(132));
    }

    private static String truncate(String value, int length) {
        if (value.length() <= length) {
            return value;
        }
        return value.substring(0, Math.max(0, length - 3)) + "...";
    }

    record EvaluationCandidate(
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

    private record CandidateKey(
            BinaryGroundTruth groundTruth,
            String nativeLabel,
            String service,
            String pattern,
            Instant observedAt
    ) {
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
        private final Instant observedAt;
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

        private EvaluationCandidate toImmutable() {
            return new EvaluationCandidate(name, pattern, service, incidentFamily, nativeLabel, message, embedding, groundTruth, eventCount, observedAt);
        }
    }

    public record EvaluationRange(
            Instant start,
            Instant end
    ) {
        public boolean contains(Instant timestamp) {
            return !timestamp.isBefore(start) && timestamp.isBefore(end);
        }
    }
}
