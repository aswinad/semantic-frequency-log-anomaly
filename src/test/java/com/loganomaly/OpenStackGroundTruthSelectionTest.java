package com.loganomaly;

import com.loganomaly.loghub.OpenStackLogHubDataset;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackSourceRole;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenStackGroundTruthSelectionTest {
    @Test
    void openStackBinaryGroundTruthUsesLabeledVmLinesAndNormalFiles() throws Exception {
        List<OpenStackLogRecord> records = new OpenStackLogHubDataset(Path.of("data/loghub/openstack")).loadAll();

        long positiveCount = records.stream()
                .filter(record -> record.role() == OpenStackSourceRole.ABNORMAL_TEST)
                .filter(record -> record.incidentFamily().equals("openstack-anomaly-vm"))
                .count();
        long negativeCount = records.stream()
                .filter(record -> record.role() == OpenStackSourceRole.NORMAL_BASELINE)
                .count();
        long excludedCount = records.stream()
                .filter(record -> record.role() == OpenStackSourceRole.ABNORMAL_TEST)
                .filter(record -> !record.incidentFamily().equals("openstack-anomaly-vm"))
                .count();

        assertTrue(positiveCount > 0, "Expected labeled anomaly VM lines");
        assertTrue(negativeCount > 0, "Expected normal-file lines");
        assertTrue(excludedCount > 0, "Expected unlabeled abnormal-file lines to exclude from binary denominators");
    }
}
