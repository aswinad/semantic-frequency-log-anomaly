package com.loganomaly.app;

import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackSourceRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenStackEvaluationWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void evaluationBackfillsMissingCandidateEmbeddingsWithoutTouchingExcludedAbnormalLines() throws Exception {
        EmbeddingCache cache = new EmbeddingCache(
                tempDir.resolve("openstack-eval-cache.jsonl"),
                "deterministic-synthetic-v1",
                12
        );
        cache.load();

        OpenStackLogRecord positive = new OpenStackLogRecord(
                "openstack_abnormal.log",
                1,
                OpenStackSourceRole.ABNORMAL_TEST,
                Instant.parse("2017-05-14T19:39:00Z"),
                "nova.api",
                "ERROR",
                Optional.of("vm-1"),
                "Database connection timeout for VM vm-1",
                "database connection timeout for vm <uuid>",
                "openstack-anomaly-vm"
        );
        OpenStackLogRecord negative = new OpenStackLogRecord(
                "openstack_normal1.log",
                2,
                OpenStackSourceRole.NORMAL_BASELINE,
                Instant.parse("2017-05-16T00:00:00Z"),
                "nova.api",
                "INFO",
                Optional.empty(),
                "Health check completed",
                "health check completed",
                "openstack-normal"
        );
        OpenStackLogRecord excluded = new OpenStackLogRecord(
                "openstack_abnormal.log",
                3,
                OpenStackSourceRole.ABNORMAL_TEST,
                Instant.parse("2017-05-14T19:40:00Z"),
                "nova.api",
                "INFO",
                Optional.empty(),
                "Background reconciliation completed",
                "background reconciliation completed",
                "openstack-normal"
        );

        OpenStackEvaluationWorkflow.ensureCandidateEmbeddings(
                List.of(positive, negative, excluded),
                cache,
                new DeterministicEmbeddingProvider(),
                2
        );

        assertTrue(cache.get(positive.pattern()).isPresent());
        assertTrue(cache.get(negative.pattern()).isPresent());
        assertFalse(cache.get(excluded.pattern()).isPresent());
    }
}
