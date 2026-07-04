package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.config.BglConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnalysisResult;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
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
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BglEvaluationWorkflow {
    private static final int TOTAL_PHASES = 5;
    private static final int SAMPLE_ROW_LIMIT = 20;
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
    private static final List<String> STRICT_SUSPICIOUS_PHRASES = List.of(
            "mount failed",
            "failed",
            "error reading",
            "error receiving",
            "connection reset",
            "cannot allocate",
            "resource busy",
            "panic",
            "stopping execution",
            "kernel terminated",
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
        run(false);
    }

    public void run(boolean includeAblations) throws IOException {
        Instant runStartedAt = Instant.now();
        PhaseTimings timings = new PhaseTimings();
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
        Optional<BglAblationCache> ablationCache = Optional.empty();
        if (includeAblations) {
            BglAblationCache loadedCache = new BglAblationCache(config.ablationCache());
            loadedCache.load(config.clearAblationCache());
            ablationCache = Optional.of(loadedCache);
            System.out.printf("BGL ablation cache: %s (clear=%s)%n", config.ablationCache(), config.clearAblationCache());
            System.out.printf("BGL ablation parallel: %s (maxWorkers=%d)%n", config.ablationParallel(), config.ablationMaxWorkers());
        }
        int totalPhases = includeAblations ? TOTAL_PHASES : 3;
        logPhaseStart(System.out, 1, totalPhases, "Preparing candidate embeddings");
        Instant phaseStartedAt = Instant.now();
        ensureCandidateEmbeddings(dataset, cache, embeddingProvider, config.batchSize(), warmupCutoff, evaluationRange, config.candidateMode(), "main");
        timings.candidatePreparation = Duration.between(phaseStartedAt, Instant.now());
        logPhaseComplete(System.out, "Preparing candidate embeddings", timings.candidatePreparation);

        logPhaseStart(System.out, 2, totalPhases, "Building evaluation candidates");
        phaseStartedAt = Instant.now();
        List<EvaluationCandidate> candidates = buildCandidates(dataset, cache, config.evalBucket(), warmupCutoff, evaluationRange, config.candidateMode());
        timings.candidateBuild = Duration.between(phaseStartedAt, Instant.now());
        System.out.printf("Built %,d grouped evaluation candidates%n", candidates.size());
        logPhaseComplete(System.out, "Building evaluation candidates", timings.candidateBuild);

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
            System.out.printf("Minimum historical support: %d%n", config.minimumHistoricalSupport());
            System.out.printf("Evaluation slice: start=%s, end=%s, durationDays=%d, mode=%s%n",
                    evaluationRange.start(),
                    evaluationRange.end(),
                    config.evalDuration().toDays(),
                    config.evalRangeMode());
            System.out.printf("Eligible evaluation rows: %,d (weighted positives=%d, weighted negatives=%d)%n%n",
                    candidates.size(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.ANOMALY).mapToLong(EvaluationCandidate::eventCount).sum(),
                    candidates.stream().filter(candidate -> candidate.groundTruth() == BinaryGroundTruth.NORMAL).mapToLong(EvaluationCandidate::eventCount).sum());

            logPhaseStart(System.out, 3, totalPhases, "Main evaluation");
            phaseStartedAt = Instant.now();
            List<PublicDatasetEvaluationResult> results = evaluateCandidates(
                    repository,
                    candidates,
                    experiment,
                    config.minimumHistoricalSupport(),
                    config.minimumAlertShortSupport(),
                    true,
                    config.verboseRowLogging(),
                    "Main evaluation",
                    ablationCache,
                    config.candidateMode(),
                    evaluationRange,
                    config.evalBucket()
            );
            timings.mainEvaluation = Duration.between(phaseStartedAt, Instant.now());
            logPhaseComplete(System.out, "Main evaluation", timings.mainEvaluation);

            List<ThresholdSweepResult> thresholdSweep = List.of();
            List<CandidateStrategyComparisonRow> candidateStrategyComparison = List.of();
            if (includeAblations) {
                logPhaseStart(System.out, 4, totalPhases, "Threshold sweep");
                phaseStartedAt = Instant.now();
                thresholdSweep = evaluateThresholdSweep(repository, candidates, config, ablationCache.orElseThrow(), evaluationRange);
                timings.thresholdSweep = Duration.between(phaseStartedAt, Instant.now());
                logPhaseComplete(System.out, "Threshold sweep", timings.thresholdSweep);

                logPhaseStart(System.out, 5, totalPhases, "Candidate strategy comparison");
                phaseStartedAt = Instant.now();
                candidateStrategyComparison = evaluateCandidateStrategyComparison(
                    repository,
                    dataset,
                    cache,
                    experiment,
                    config,
                    warmupCutoff,
                    evaluationRange,
                    ablationCache.orElseThrow()
                );
                timings.candidateStrategyComparison = Duration.between(phaseStartedAt, Instant.now());
                logPhaseComplete(System.out, "Candidate strategy comparison", timings.candidateStrategyComparison);
            }

            if (appConfig.report().excelEnabled()) {
                Instant workbookStartedAt = Instant.now();
                String workbookTarget = Path.of(
                        appConfig.report().outputDir(),
                        "%s-<timestamp>.xlsx".formatted(appConfig.report().filePrefix())
                ).toString();
                System.out.printf("Final: Writing BGL workbook to %s%n", workbookTarget);
                try {
                    Path workbookPath = new PublicDatasetWorkbookWriter().writeBgl(
                            appConfig,
                            results,
                            Instant.now(),
                            evaluationRange,
                            thresholdSweep,
                            candidateStrategyComparison
                    );
                    timings.workbookWriting = Duration.between(workbookStartedAt, Instant.now());
                    System.out.printf("%nBGL paper metrics report: %s%n", workbookPath.toAbsolutePath());
                    logTimingSummary(timings, Duration.between(runStartedAt, Instant.now()));
                } catch (IOException | RuntimeException exception) {
                    System.out.printf("Workbook generation failed during BGL report writing: %s%n", exception.getMessage());
                    throw exception;
                }
            } else {
                logTimingSummary(timings, Duration.between(runStartedAt, Instant.now()));
            }
        }
    }

    private List<PublicDatasetEvaluationResult> evaluateCandidates(
            BglOpenSearchRepository repository,
            List<EvaluationCandidate> candidates,
            ExperimentConfig experiment,
            int minimumHistoricalSupport,
            int minimumAlertShortSupport,
            boolean printRows,
            boolean verboseRowLogging,
            String phaseLabel,
            Optional<BglAblationCache> ablationCache,
            BglCandidateMode candidateMode,
            EvaluationRange evaluationRange,
            Duration evalBucket
    ) throws IOException {
        BglOpenSearchHybridAnalyzer analyzer = new BglOpenSearchHybridAnalyzer(
                repository,
                new HybridAnomalyDetector(0.5, 0.5),
                experiment,
                minimumHistoricalSupport,
                phaseLabel
        );
        List<PublicDatasetEvaluationResult> results = new ArrayList<>(candidates.size());
        ProgressReporter progressReporter = new ProgressReporter(phaseLabel, candidates.size(), verboseRowLogging, printRows);
        CacheStats cacheStats = countCacheStats(
                ablationCache,
                candidates,
                candidateMode,
                experiment,
                minimumHistoricalSupport,
                minimumAlertShortSupport,
                evaluationRange,
                evalBucket
        );
        if (ablationCache.isPresent()) {
            System.out.printf("%s: loaded %,d cached rows, recomputing %,d rows%n", phaseLabel, cacheStats.hits(), cacheStats.misses());
        }
        if (printRows) {
            printHeader();
        }
        for (int index = 0; index < candidates.size(); index++) {
            EvaluationCandidate candidate = candidates.get(index);
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
            String cacheKey = BglAblationCache.cacheKey(
                    candidateMode,
                    experiment,
                    minimumHistoricalSupport,
                    minimumAlertShortSupport,
                    evaluationRange,
                    evalBucket,
                    candidate
            );
            ScenarioResult scenarioResult;
            try {
                scenarioResult = ablationCache.flatMap(cache -> cache.get(cacheKey))
                        .map(payload -> scenarioResultFromCachePayload(probe, payload, experiment, minimumHistoricalSupport))
                        .orElseGet(() -> {
                            try {
                                ScenarioResult analyzed = analyzer.analyze(probe);
                                if (ablationCache.isPresent()) {
                                    ablationCache.orElseThrow().putIfAbsent(cacheKey, BglAblationCache.fromScenarioResult(analyzed));
                                }
                                return analyzed;
                            } catch (IOException e) {
                                throw new EvaluationIOException(e);
                            }
                        });
            } catch (EvaluationIOException e) {
                throw e.unwrap();
            }
            AnomalyClass exactPatternClass = classifyExactPattern(scenarioResult);
            AnomalyClass topKClass = classifyTopK(scenarioResult, experiment);
            AnomalyClass semanticFrequencyClass = classifySemanticFrequency(scenarioResult);
            AnomalyClass semanticTemporalClass = classifySemanticTemporal(scenarioResult, minimumAlertShortSupport);
            AnomalyClass hybridClass = classifyHybrid(scenarioResult, minimumAlertShortSupport);
            PublicDatasetEvaluationResult result = new PublicDatasetEvaluationResult(
                    candidate.name(),
                    candidate.groundTruth(),
                    candidate.nativeLabel(),
                    candidate.eventCount(),
                    scenarioResult,
                    exactPatternClass,
                    topKClass,
                    semanticFrequencyClass,
                    semanticTemporalClass,
                    hybridClass
            );
            results.add(result);

            if (printRows) {
                progressReporter.logRow(result);
            }
            progressReporter.logProgress(index + 1);
        }
        return results;
    }

    static ScenarioResult scenarioResultFromCachePayload(
            ScenarioProbe probe,
            BglAblationCache.CachedScenarioPayload payload,
            ExperimentConfig experiment,
            int minimumHistoricalSupport
    ) {
        SemanticAnalysis semantic = new SemanticAnalysis(
                payload.semanticCount(),
                payload.semanticSimilarityScore(),
                experiment.noveltyThreshold()
        );
        TemporalAnalysis temporal = new TemporalAnalysis(
                payload.temporalShortCount(),
                payload.temporalLongCount(),
                experiment.shortWindow(),
                experiment.baselineWindow(),
                experiment.spikeThreshold(),
                minimumHistoricalSupport,
                true
        );
        TemporalAnalysis exactTemporal = new TemporalAnalysis(
                payload.exactShortCount(),
                payload.exactLongCount(),
                experiment.shortWindow(),
                experiment.baselineWindow(),
                experiment.spikeThreshold(),
                minimumHistoricalSupport,
                true
        );
        HybridAnalysisResult hybrid = new HybridAnomalyDetector(0.5, 0.5).analyze(semantic, temporal);
        return new ScenarioResult(
                probe,
                semantic,
                temporal,
                hybrid,
                payload.neighbors().stream().map(BglAblationCache.CachedNeighbor::toKnnNeighbor).toList(),
                exactTemporal
        );
    }

    private static CacheStats countCacheStats(
            Optional<BglAblationCache> ablationCache,
            List<EvaluationCandidate> candidates,
            BglCandidateMode candidateMode,
            ExperimentConfig experiment,
            int minimumHistoricalSupport,
            int minimumAlertShortSupport,
            EvaluationRange evaluationRange,
            Duration evalBucket
    ) {
        if (ablationCache.isEmpty()) {
            return new CacheStats(0, candidates.size());
        }
        int hits = 0;
        for (EvaluationCandidate candidate : candidates) {
            String cacheKey = BglAblationCache.cacheKey(
                    candidateMode,
                    experiment,
                    minimumHistoricalSupport,
                    minimumAlertShortSupport,
                    evaluationRange,
                    evalBucket,
                    candidate
            );
            if (ablationCache.orElseThrow().get(cacheKey).isPresent()) {
                hits++;
            }
        }
        return new CacheStats(hits, candidates.size() - hits);
    }

    private List<ThresholdSweepResult> evaluateThresholdSweep(
            BglOpenSearchRepository repository,
            List<EvaluationCandidate> candidates,
            BglConfig config,
            BglAblationCache ablationCache,
            EvaluationRange evaluationRange
    ) throws IOException {
        if (config.ablationParallel()) {
            return evaluateThresholdSweepParallel(candidates, config, ablationCache, evaluationRange);
        }
        List<ThresholdSweepResult> results = new ArrayList<>();
        List<Double> sweepThresholds = config.similaritySweep();
        for (int index = 0; index < sweepThresholds.size(); index++) {
            double threshold = sweepThresholds.get(index);
            String phaseLabel = "Threshold sweep [tsim=%.2f]".formatted(threshold);
            System.out.printf("%s started%n", phaseLabel);
            ExperimentConfig sweepExperiment = new ExperimentConfig(
                    config.shortWindow(),
                    config.baselineWindow(),
                    appConfig.experiment().topK(),
                    appConfig.experiment().noveltyThreshold(),
                    threshold,
                    appConfig.experiment().spikeThreshold()
            );
            List<PublicDatasetEvaluationResult> sweepResults =
                    evaluateCandidates(
                            repository,
                            candidates,
                            sweepExperiment,
                            config.minimumHistoricalSupport(),
                            config.minimumAlertShortSupport(),
                            false,
                            config.verboseRowLogging(),
                            phaseLabel,
                            Optional.of(ablationCache),
                            config.candidateMode(),
                            evaluationRange,
                            config.evalBucket()
                    );
            results.add(new ThresholdSweepResult(
                    threshold,
                    PublicDatasetEvaluation.detectionMetrics(EvaluationMethod.HYBRID_FRAMEWORK, sweepResults)
            ));
            System.out.printf("%s completed%n", phaseLabel);
        }
        return results;
    }

    private List<CandidateStrategyComparisonRow> evaluateCandidateStrategyComparison(
            BglOpenSearchRepository repository,
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            ExperimentConfig experiment,
            BglConfig config,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglAblationCache ablationCache
    ) throws IOException {
        if (config.ablationParallel()) {
            return evaluateCandidateStrategyComparisonParallel(dataset, cache, experiment, config, warmupCutoff, evaluationRange, ablationCache);
        }
        List<CandidateStrategyComparisonRow> comparisonRows = new ArrayList<>();
        List<BglCandidateMode> strategyModes = List.of(BglCandidateMode.ALL, BglCandidateMode.FILTERED, BglCandidateMode.STRICT);
        for (int index = 0; index < strategyModes.size(); index++) {
            BglCandidateMode candidateMode = strategyModes.get(index);
            String phaseLabel = "Candidate strategy [%s]".formatted(candidateMode);
            System.out.printf("%s started%n", phaseLabel);
            ensureCandidateEmbeddings(
                    dataset,
                    cache,
                    embeddingProvider,
                    config.batchSize(),
                    warmupCutoff,
                    evaluationRange,
                    candidateMode,
                    "strategy:" + candidateMode
            );
            List<EvaluationCandidate> strategyCandidates = buildCandidates(
                    dataset,
                    cache,
                    config.evalBucket(),
                    warmupCutoff,
                    evaluationRange,
                    candidateMode
            );
            List<PublicDatasetEvaluationResult> strategyResults = evaluateCandidates(
                    repository,
                    strategyCandidates,
                    experiment,
                    config.minimumHistoricalSupport(),
                    config.minimumAlertShortSupport(),
                    false,
                    config.verboseRowLogging(),
                    phaseLabel,
                    Optional.of(ablationCache),
                    candidateMode,
                    evaluationRange,
                    config.evalBucket()
            );
            long totalEvents = strategyResults.stream().mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
            comparisonRows.add(new CandidateStrategyComparisonRow(
                    candidateMode.toString(),
                    strategyCandidates.size(),
                    totalEvents,
                    PublicDatasetEvaluation.detectionMetrics(EvaluationMethod.HYBRID_FRAMEWORK, strategyResults)
            ));
            System.out.printf("%s completed%n", phaseLabel);
        }
        return comparisonRows;
    }

    private List<ThresholdSweepResult> evaluateThresholdSweepParallel(
            List<EvaluationCandidate> candidates,
            BglConfig config,
            BglAblationCache ablationCache,
            EvaluationRange evaluationRange
    ) throws IOException {
        List<Double> thresholds = config.similaritySweep();
        List<Callable<ThresholdSweepResult>> tasks = new ArrayList<>();
        for (double threshold : thresholds) {
            tasks.add(() -> {
                String phaseLabel = "Threshold sweep [tsim=%.2f]".formatted(threshold);
                Instant startedAt = Instant.now();
                System.out.printf("%s started%n", phaseLabel);
                try (BglOpenSearchRepository workerRepository = BglOpenSearchRepository.fromConfig(appConfig, config.indexName())) {
                    ExperimentConfig sweepExperiment = new ExperimentConfig(
                            config.shortWindow(),
                            config.baselineWindow(),
                            appConfig.experiment().topK(),
                            appConfig.experiment().noveltyThreshold(),
                            threshold,
                            appConfig.experiment().spikeThreshold()
                    );
                    List<PublicDatasetEvaluationResult> sweepResults = evaluateCandidates(
                            workerRepository,
                            candidates,
                            sweepExperiment,
                            config.minimumHistoricalSupport(),
                            config.minimumAlertShortSupport(),
                            false,
                            config.verboseRowLogging(),
                            phaseLabel,
                            Optional.of(ablationCache),
                            config.candidateMode(),
                            evaluationRange,
                            config.evalBucket()
                    );
                    System.out.printf("%s completed in %s%n", phaseLabel, formatDuration(Duration.between(startedAt, Instant.now())));
                    return new ThresholdSweepResult(
                            threshold,
                            PublicDatasetEvaluation.detectionMetrics(EvaluationMethod.HYBRID_FRAMEWORK, sweepResults)
                    );
                } catch (EvaluationIOException e) {
                    throw e.unwrap();
                }
            });
        }
        List<ThresholdSweepResult> results = runParallelTasks(tasks, config.ablationMaxWorkers());
        return results.stream()
                .sorted(Comparator.comparingDouble(ThresholdSweepResult::similarityThreshold))
                .toList();
    }

    private List<CandidateStrategyComparisonRow> evaluateCandidateStrategyComparisonParallel(
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            ExperimentConfig experiment,
            BglConfig config,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglAblationCache ablationCache
    ) throws IOException {
        List<BglCandidateMode> strategyModes = List.of(BglCandidateMode.ALL, BglCandidateMode.FILTERED, BglCandidateMode.STRICT);
        for (BglCandidateMode candidateMode : strategyModes) {
            ensureCandidateEmbeddings(
                    dataset,
                    cache,
                    embeddingProvider,
                    config.batchSize(),
                    warmupCutoff,
                    evaluationRange,
                    candidateMode,
                    "strategy:" + candidateMode
            );
        }
        List<Callable<CandidateStrategyComparisonRow>> tasks = new ArrayList<>();
        for (BglCandidateMode candidateMode : strategyModes) {
            tasks.add(() -> {
                String phaseLabel = "Candidate strategy [%s]".formatted(candidateMode);
                Instant startedAt = Instant.now();
                System.out.printf("%s started%n", phaseLabel);
                List<EvaluationCandidate> strategyCandidates = buildCandidates(
                        dataset,
                        cache,
                        config.evalBucket(),
                        warmupCutoff,
                        evaluationRange,
                        candidateMode
                );
                try (BglOpenSearchRepository workerRepository = BglOpenSearchRepository.fromConfig(appConfig, config.indexName())) {
                    List<PublicDatasetEvaluationResult> strategyResults = evaluateCandidates(
                            workerRepository,
                            strategyCandidates,
                            experiment,
                            config.minimumHistoricalSupport(),
                            config.minimumAlertShortSupport(),
                            false,
                            config.verboseRowLogging(),
                            phaseLabel,
                            Optional.of(ablationCache),
                            candidateMode,
                            evaluationRange,
                            config.evalBucket()
                    );
                    long totalEvents = strategyResults.stream().mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
                    System.out.printf("%s completed in %s%n", phaseLabel, formatDuration(Duration.between(startedAt, Instant.now())));
                    return new CandidateStrategyComparisonRow(
                            candidateMode.toString(),
                            strategyCandidates.size(),
                            totalEvents,
                            PublicDatasetEvaluation.detectionMetrics(EvaluationMethod.HYBRID_FRAMEWORK, strategyResults)
                    );
                } catch (EvaluationIOException e) {
                    throw e.unwrap();
                }
            });
        }
        List<CandidateStrategyComparisonRow> results = runParallelTasks(tasks, config.ablationMaxWorkers());
        return results.stream()
                .sorted(Comparator.comparingInt(row -> strategyOrder(BglCandidateMode.parse(row.candidateMode()))))
                .toList();
    }

    private <T> List<T> runParallelTasks(List<Callable<T>> tasks, int maxWorkers) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxWorkers, tasks.size()));
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<T> results = new ArrayList<>(tasks.size());
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.add(futures.get(index).get());
                } catch (InterruptedException e) {
                    cancelAll(futures);
                    Thread.currentThread().interrupt();
                    throw new IOException("Parallel ablation execution interrupted", e);
                } catch (ExecutionException e) {
                    cancelAll(futures);
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Parallel ablation execution failed", cause);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static int strategyOrder(BglCandidateMode mode) {
        return switch (mode) {
            case ALL -> 0;
            case FILTERED -> 1;
            case STRICT -> 2;
        };
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
        ensureCandidateEmbeddings(dataset, cache, embeddingProvider, batchSize, warmupCutoff, evaluationRange, candidateMode, "main");
    }

    static void ensureCandidateEmbeddings(
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            EmbeddingProvider embeddingProvider,
            int batchSize,
            Instant warmupCutoff,
            EvaluationRange evaluationRange,
            BglCandidateMode candidateMode,
            String phaseLabel
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
        System.out.printf("[%s] candidate templates missing from cache: %,d%n", phaseLabel, missingTemplates.size());
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
            System.out.printf("[%s] cached evaluation embeddings %,d/%,d%n", phaseLabel, to, templates.size());
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
            case STRICT -> isStrictSuspiciousCandidate(record);
        };
    }

    static EvaluationRange resolveEvaluationRange(BglConfig config, Instant warmupCutoff) {
        Instant start = config.evalStart().orElse(warmupCutoff);
        if (start.isBefore(warmupCutoff)) {
            start = warmupCutoff;
        }
        return new EvaluationRange(start, start.plus(config.evalDuration()));
    }

    static boolean isSuspiciousCandidate(BglLogRecord record) {
        return containsSuspiciousPhrase(record.pattern(), SUSPICIOUS_PHRASES);
    }

    static boolean isStrictSuspiciousCandidate(BglLogRecord record) {
        return containsSuspiciousPhrase(record.pattern(), STRICT_SUSPICIOUS_PHRASES);
    }

    private static boolean containsSuspiciousPhrase(String pattern, List<String> phrases) {
        for (String phrase : phrases) {
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

    static boolean looseSemanticPositive(ScenarioResult result, int minimumAlertShortSupport) {
        return result.temporal().signal() == com.loganomaly.core.TemporalSignal.SPIKE
                && result.temporal().shortCount() >= minimumAlertShortSupport;
    }

    static boolean strictAgreementPositive(ScenarioResult result, int minimumAlertShortSupport) {
        return looseSemanticPositive(result, minimumAlertShortSupport)
                && result.exactPatternBaseline().signal() == com.loganomaly.core.TemporalSignal.SPIKE;
    }

    private static AnomalyClass classifyExactPattern(ScenarioResult result) {
        return result.exactPatternBaseline().signal() == com.loganomaly.core.TemporalSignal.SPIKE
                ? AnomalyClass.SURGE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass classifyTopK(ScenarioResult result, ExperimentConfig config) {
        return result.semantic().semanticSimilarityScore() < config.similarityThreshold()
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass classifySemanticFrequency(ScenarioResult result) {
        return result.semantic().signal() == com.loganomaly.core.SemanticSignal.NOVEL
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass classifySemanticTemporal(ScenarioResult result, int minimumAlertShortSupport) {
        return looseSemanticPositive(result, minimumAlertShortSupport)
                ? AnomalyClass.SURGE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static AnomalyClass classifyHybrid(ScenarioResult result, int minimumAlertShortSupport) {
        if (looseSemanticPositive(result, minimumAlertShortSupport)) {
            return result.hybrid().anomalyClass();
        }
        return result.semantic().signal() == com.loganomaly.core.SemanticSignal.NOVEL
                ? AnomalyClass.RARE_ANOMALY
                : AnomalyClass.NORMAL_BEHAVIOR;
    }

    private static com.loganomaly.report.DetectionMetrics detectionMetrics(
            List<PublicDatasetEvaluationResult> results,
            java.util.function.Predicate<PublicDatasetEvaluationResult> predictedPositive
    ) {
        long truePositive = 0;
        long falsePositive = 0;
        long trueNegative = 0;
        long falseNegative = 0;
        for (PublicDatasetEvaluationResult result : results) {
            boolean predicted = predictedPositive.test(result);
            long weight = result.eventCount();
            if (predicted && result.actualAnomaly()) {
                truePositive += weight;
            } else if (predicted) {
                falsePositive += weight;
            } else if (result.actualAnomaly()) {
                falseNegative += weight;
            } else {
                trueNegative += weight;
            }
        }
        return com.loganomaly.report.DetectionMetrics.fromCounts(truePositive, falsePositive, trueNegative, falseNegative);
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

    static void logPhaseStart(PrintStream out, int phaseIndex, int totalPhases, String label) {
        out.printf("Phase %d/%d: %s%n", phaseIndex, totalPhases, label);
    }

    static void logPhaseComplete(PrintStream out, String label, Duration elapsed) {
        out.printf("Completed %s in %s%n", label, formatDuration(elapsed));
    }

    private static void logTimingSummary(PhaseTimings timings, Duration totalRuntime) {
        System.out.println();
        System.out.println("BGL timing summary:");
        System.out.printf("  Candidate preparation: %s%n", formatDuration(timings.candidatePreparation));
        System.out.printf("  Candidate build: %s%n", formatDuration(timings.candidateBuild));
        System.out.printf("  Main evaluation: %s%n", formatDuration(timings.mainEvaluation));
        if (!timings.thresholdSweep.isZero()) {
            System.out.printf("  Threshold sweep: %s%n", formatDuration(timings.thresholdSweep));
        }
        if (!timings.candidateStrategyComparison.isZero()) {
            System.out.printf("  Candidate strategy comparison: %s%n", formatDuration(timings.candidateStrategyComparison));
        }
        System.out.printf("  Workbook writing: %s%n", formatDuration(timings.workbookWriting));
        System.out.printf("  Total runtime: %s%n", formatDuration(totalRuntime));
    }

    static String progressMessage(String phaseLabel, int completed, int total) {
        double percent = total == 0 ? 100.0 : (completed * 100.0) / total;
        return "%s progress: %,d/%,d rows (%.1f%%)".formatted(phaseLabel, completed, total, percent);
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes > 0 ? "%dm%02ds".formatted(minutes, remainingSeconds) : "%ds".formatted(remainingSeconds);
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

    public record ThresholdSweepResult(
            double similarityThreshold,
            com.loganomaly.report.DetectionMetrics metrics
    ) {
    }

    public record CandidateStrategyComparisonRow(
            String candidateMode,
            int evaluationRows,
            long weightedEvents,
            com.loganomaly.report.DetectionMetrics metrics
    ) {
    }

    private static final class PhaseTimings {
        private Duration candidatePreparation = Duration.ZERO;
        private Duration candidateBuild = Duration.ZERO;
        private Duration mainEvaluation = Duration.ZERO;
        private Duration thresholdSweep = Duration.ZERO;
        private Duration candidateStrategyComparison = Duration.ZERO;
        private Duration workbookWriting = Duration.ZERO;
    }

    private record CacheStats(int hits, int misses) {
    }

    private static final class EvaluationIOException extends RuntimeException {
        private final IOException cause;

        private EvaluationIOException(IOException cause) {
            super(cause);
            this.cause = cause;
        }

        private IOException unwrap() {
            return cause;
        }
    }

    private static final class ProgressReporter {
        private final String phaseLabel;
        private final int totalRows;
        private final boolean verboseRowLogging;
        private final boolean rowLoggingEnabled;
        private final int progressInterval;
        private int detailedRowsPrinted;

        private ProgressReporter(String phaseLabel, int totalRows, boolean verboseRowLogging, boolean rowLoggingEnabled) {
            this.phaseLabel = phaseLabel;
            this.totalRows = totalRows;
            this.verboseRowLogging = verboseRowLogging;
            this.rowLoggingEnabled = rowLoggingEnabled;
            this.progressInterval = computeProgressInterval(totalRows);
        }

        private void logRow(PublicDatasetEvaluationResult result) {
            if (!rowLoggingEnabled) {
                return;
            }
            if (!verboseRowLogging && detailedRowsPrinted >= SAMPLE_ROW_LIMIT) {
                return;
            }
            ScenarioResult scenarioResult = result.scenarioResult();
            System.out.printf(
                    "%-34s %-10s %8d %8d %8d %8d %8.2f %-18s %-12s%n",
                    truncate(scenarioResult.probe().pattern(), 34),
                    result.nativeLabel(),
                    result.eventCount(),
                    scenarioResult.exactPatternBaseline().shortCount(),
                    scenarioResult.exactPatternBaseline().longCount(),
                    scenarioResult.temporal().shortCount(),
                    scenarioResult.temporal().spikeRatio(),
                    result.predictedClass(EvaluationMethod.HYBRID_FRAMEWORK),
                    PublicDatasetEvaluation.isPositiveForResult(result, EvaluationMethod.HYBRID_FRAMEWORK) ? "ANOMALY" : "NORMAL"
            );
            detailedRowsPrinted++;
            if (!verboseRowLogging && detailedRowsPrinted == SAMPLE_ROW_LIMIT) {
                System.out.printf("%s detailed row sample limit reached (%d). Switching to periodic progress updates.%n",
                        phaseLabel, SAMPLE_ROW_LIMIT);
            }
        }

        private void logProgress(int completedRows) {
            if (totalRows == 0) {
                return;
            }
            if (completedRows == totalRows || completedRows % progressInterval == 0) {
                System.out.println(progressMessage(phaseLabel, completedRows, totalRows));
            }
        }

        private static int computeProgressInterval(int totalRows) {
            if (totalRows <= 0) {
                return 1;
            }
            int tenPercent = Math.max(1, totalRows / 10);
            return Math.min(100, tenPercent);
        }
    }
}
