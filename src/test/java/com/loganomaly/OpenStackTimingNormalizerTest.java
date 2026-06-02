package com.loganomaly;

import com.loganomaly.loghub.OpenStackSourceRole;
import com.loganomaly.loghub.OpenStackTimingNormalizer;
import com.loganomaly.opensearch.LogDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenStackTimingNormalizerTest {
    private final OpenStackTimingNormalizer normalizer = OpenStackTimingNormalizer.forLogHubDefaults();

    @Test
    void mapsNormalFilesIntoBaselineExperimentWindow() {
        Instant firstNormalTimestamp = Instant.parse("2017-05-16T00:00:00.008Z");
        Instant lastNormalTimestamp = Instant.parse("2017-05-17T12:02:35.320Z");

        assertEquals(
                Instant.parse("2026-01-01T00:00:00Z"),
                normalizer.normalize(OpenStackSourceRole.NORMAL_BASELINE, firstNormalTimestamp)
        );
        assertEquals(
                Instant.parse("2026-01-01T23:45:00Z"),
                normalizer.normalize(OpenStackSourceRole.NORMAL_BASELINE, lastNormalTimestamp)
        );
    }

    @Test
    void mapsAbnormalFileIntoFinalTestWindow() {
        Instant firstAbnormalTimestamp = Instant.parse("2017-05-14T19:39:01.445Z");
        Instant middleAbnormalTimestamp = Instant.parse("2017-05-14T20:47:43.488Z");
        Instant lastAbnormalTimestamp = Instant.parse("2017-05-14T21:56:25.531Z");

        assertEquals(
                Instant.parse("2026-01-01T23:45:00Z"),
                normalizer.normalize(OpenStackSourceRole.ABNORMAL_TEST, firstAbnormalTimestamp)
        );

        Instant normalizedMiddle = normalizer.normalize(OpenStackSourceRole.ABNORMAL_TEST, middleAbnormalTimestamp);
        assertFalse(normalizedMiddle.isBefore(Instant.parse("2026-01-01T23:45:00Z")));
        assertFalse(normalizedMiddle.isAfter(Instant.parse("2026-01-02T00:00:00Z")));

        assertEquals(
                Instant.parse("2026-01-02T00:00:00Z"),
                normalizer.normalize(OpenStackSourceRole.ABNORMAL_TEST, lastAbnormalTimestamp)
        );
    }

    @Test
    void logDocumentKeepsOriginalTimestampSeparateFromExperimentTimestamp() {
        Instant originalTimestamp = Instant.parse("2017-05-14T20:47:43.488Z");
        Instant experimentTimestamp = normalizer.normalize(OpenStackSourceRole.ABNORMAL_TEST, originalTimestamp);

        LogDocument document = new LogDocument(
                "openstack-1",
                experimentTimestamp,
                originalTimestamp,
                "nova.compute.manager",
                "vm-paused-lifecycle-event",
                "openstack-anomaly-vm",
                "openstack-anomaly-vm",
                "openstack_abnormal",
                "VM Paused (Lifecycle Event)",
                new float[]{1.0f, 0.0f}
        );

        assertEquals(experimentTimestamp, document.timestamp());
        assertEquals(originalTimestamp, document.originalTimestamp());
    }
}
