package com.loganomaly.loghub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OpenStackLogHubDataset {
    private final Path directory;
    private final OpenStackLogParser parser = new OpenStackLogParser();

    public OpenStackLogHubDataset(Path directory) {
        this.directory = directory;
    }

    public List<OpenStackLogRecord> loadAll() throws IOException {
        Set<String> anomalyVmIds = loadAnomalyVmIds();
        List<OpenStackLogRecord> records = new ArrayList<>();
        loadFile(records, "openstack_normal1.log", OpenStackSourceRole.NORMAL_BASELINE, anomalyVmIds);
        loadFile(records, "openstack_normal2.log", OpenStackSourceRole.NORMAL_BASELINE, anomalyVmIds);
        loadFile(records, "openstack_abnormal.log", OpenStackSourceRole.ABNORMAL_TEST, anomalyVmIds);
        return records;
    }

    public List<OpenStackLogRecord> loadAnomalousAbnormalRecords() throws IOException {
        Set<String> anomalyVmIds = loadAnomalyVmIds();
        List<OpenStackLogRecord> records = new ArrayList<>();
        loadFile(records, "openstack_abnormal.log", OpenStackSourceRole.ABNORMAL_TEST, anomalyVmIds);
        return records.stream()
                .filter(record -> record.incidentFamily().equals("openstack-anomaly-vm"))
                .toList();
    }

    public Set<String> loadAnomalyVmIds() throws IOException {
        Path labels = directory.resolve("anomaly_labels.txt");
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(labels)) {
            String trimmed = line.trim();
            if (trimmed.matches("[0-9a-fA-F-]{36}")) {
                ids.add(trimmed.toLowerCase());
            }
        }
        return ids;
    }

    private void loadFile(
            List<OpenStackLogRecord> records,
            String fileName,
            OpenStackSourceRole role,
            Set<String> anomalyVmIds
    ) throws IOException {
        Path file = directory.resolve(fileName);
        long lineNumber = 0;
        for (String line : Files.readAllLines(file)) {
            lineNumber++;
            records.add(parser.parse(line, lineNumber, role, anomalyVmIds));
        }
    }
}
