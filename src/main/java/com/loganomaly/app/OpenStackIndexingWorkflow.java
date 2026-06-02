package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.OpenStackConfig;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.embedding.OpenAIEmbeddingProvider;
import com.loganomaly.loghub.OpenStackLogHubDataset;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackTimingNormalizer;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenStackIndexingWorkflow {
    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public OpenStackIndexingWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
        OpenStackConfig config = appConfig.openStack();
        OpenStackLogHubDataset dataset = new OpenStackLogHubDataset(config.loghubDir());
        List<OpenStackLogRecord> records = dataset.loadAll();
        System.out.printf("Loaded %,d OpenStack LogHub records from %s%n", records.size(), config.loghubDir());

        EmbeddingCache cache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        cache.load();
        embedMissingTemplates(records, cache, config.batchSize());

        OpenStackTimingNormalizer timingNormalizer = timingNormalizer(config);
        List<LogDocument> documents = toDocuments(records, cache, timingNormalizer);

        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromConfig(appConfig, config.indexName())) {
            OpenSearchLogVectorRepository.VectorIndexPreparation indexPreparation =
                    repository.prepareVectorIndex(embeddingProvider.dimensions(), config.recreateIndex());
            if (!indexPreparation.validation().valid()) {
                throw new IllegalStateException(
                        "OpenStack index '%s' has invalid mapping: %s. Rerun with OPENSTACK_RECREATE_INDEX=true."
                                .formatted(config.indexName(), indexPreparation.validation().message())
                );
            }
            System.out.printf("OpenStack index '%s' %s%n", config.indexName(), indexPreparation.action());
            bulkIndexWithProgress(repository, documents, config.indexBatchSize());
            repository.refresh();
            OpenSearchLogVectorRepository.VectorIndexValidation validation =
                    repository.validateVectorIndex(embeddingProvider.dimensions());
            System.out.printf("Indexed %,d OpenStack documents into '%s'%n", documents.size(), config.indexName());
            System.out.printf(
                    "Index count: %,d documents, anomaly VM documents: %,d, embedding.type=%s, embedding.dimension=%d%n",
                    repository.countAll(),
                    repository.countIncidentFamily("openstack-anomaly-vm"),
                    validation.embeddingType(),
                    validation.embeddingDimension()
            );
        }
    }

    private void embedMissingTemplates(List<OpenStackLogRecord> records, EmbeddingCache cache, int batchSize) throws IOException {
        Set<String> missingTemplates = new LinkedHashSet<>();
        for (OpenStackLogRecord record : records) {
            if (cache.get(record.pattern()).isEmpty()) {
                missingTemplates.add(record.pattern());
            }
        }
        System.out.printf("Embedding cache missing %,d unique OpenStack templates%n", missingTemplates.size());
        if (missingTemplates.isEmpty()) {
            return;
        }

        List<String> templates = new ArrayList<>(missingTemplates);
        for (int from = 0; from < templates.size(); from += Math.max(1, batchSize)) {
            int to = Math.min(templates.size(), from + Math.max(1, batchSize));
            List<String> batch = templates.subList(from, to);
            List<float[]> embeddings = embedBatch(batch);
            Map<String, float[]> newEmbeddings = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                newEmbeddings.put(batch.get(i), embeddings.get(i));
            }
            cache.putAll(newEmbeddings);
            System.out.printf("Cached embeddings %,d/%,d%n", to, templates.size());
        }
    }

    private List<float[]> embedBatch(List<String> batch) {
        if (embeddingProvider instanceof OpenAIEmbeddingProvider openAIEmbeddingProvider) {
            return openAIEmbeddingProvider.embedBatch(batch);
        }
        return batch.stream().map(embeddingProvider::embed).toList();
    }

    private List<LogDocument> toDocuments(
            List<OpenStackLogRecord> records,
            EmbeddingCache cache,
            OpenStackTimingNormalizer timingNormalizer
    ) {
        List<LogDocument> documents = new ArrayList<>(records.size());
        for (OpenStackLogRecord record : records) {
            Instant experimentTimestamp = timingNormalizer.normalize(record.role(), record.originalTimestamp());
            float[] embedding = cache.get(record.pattern())
                    .orElseThrow(() -> new IllegalStateException("Missing cached embedding for " + record.pattern()));
            documents.add(new LogDocument(
                    documentId(record),
                    experimentTimestamp,
                    record.originalTimestamp(),
                    record.service(),
                    record.pattern(),
                    record.incidentFamily(),
                    record.incidentFamily(),
                    record.sourceFile(),
                    record.rawMessage(),
                    embedding
            ));
        }
        return documents;
    }

    private static OpenStackTimingNormalizer timingNormalizer(OpenStackConfig config) {
        Duration baselinePeriod = config.baselineWindow().minus(config.shortWindow());
        return OpenStackTimingNormalizer.forLogHub(config.experimentAnchor(), baselinePeriod, config.shortWindow());
    }

    private static String documentId(OpenStackLogRecord record) {
        return record.sourceFile().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + record.lineNumber();
    }

    private static void bulkIndexWithProgress(
            OpenSearchLogVectorRepository repository,
            List<LogDocument> documents,
            int batchSize
    ) throws IOException {
        int effectiveBatchSize = Math.max(1, batchSize);
        System.out.printf("Indexing %,d OpenStack documents with OpenSearch bulk batch size %,d%n",
                documents.size(),
                effectiveBatchSize);
        for (int from = 0; from < documents.size(); from += effectiveBatchSize) {
            int to = Math.min(documents.size(), from + effectiveBatchSize);
            bulkIndexRange(repository, documents, from, to, effectiveBatchSize);
            System.out.printf("Indexed %,d/%,d OpenStack documents%n", to, documents.size());
        }
    }

    private static void bulkIndexRange(
            OpenSearchLogVectorRepository repository,
            List<LogDocument> documents,
            int from,
            int to,
            int batchSize
    ) throws IOException {
        try {
            repository.bulkIndex(documents.subList(from, to), batchSize);
        } catch (IOException e) {
            if (!isRetryableTimeout(e) || (to - from) <= 100) {
                throw e;
            }
            int midpoint = from + ((to - from) / 2);
            System.out.printf(
                    "Bulk request slowed down for documents %,d-%,d. Retrying in smaller chunks (%d -> %d + %d)%n",
                    from + 1,
                    to,
                    to - from,
                    midpoint - from,
                    to - midpoint
            );
            bulkIndexRange(repository, documents, from, midpoint, Math.max(1, batchSize / 2));
            bulkIndexRange(repository, documents, midpoint, to, Math.max(1, batchSize / 2));
        }
    }

    private static boolean isRetryableTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
