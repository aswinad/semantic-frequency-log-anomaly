package com.loganomaly;

import com.loganomaly.loghub.OpenStackLogParser;
import com.loganomaly.loghub.OpenStackLogRecord;
import com.loganomaly.loghub.OpenStackSourceRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenStackLogParserTest {
    @Test
    void parsesOpenStackLineAndLabelsAnomalyVm() {
        String vmId = "544fd51c-4edc-4780-baae-ba1d80a0acfc";
        String line = "nova-compute.log.2017-05-14_21:27:09 2017-05-14 20:05:01.123 2931 INFO "
                + "nova.compute.manager [req-123 - - - - -] [instance: " + vmId + "] VM Paused (Lifecycle Event)";

        OpenStackLogRecord record = new OpenStackLogParser().parse(
                line,
                42,
                OpenStackSourceRole.ABNORMAL_TEST,
                Set.of(vmId)
        );

        assertEquals("nova-compute.log.2017-05-14_21:27:09", record.sourceFile());
        assertEquals(42, record.lineNumber());
        assertEquals(Instant.parse("2017-05-14T20:05:01.123Z"), record.originalTimestamp());
        assertEquals("INFO", record.level());
        assertEquals("nova.compute.manager", record.service());
        assertEquals(vmId, record.instanceId().orElseThrow());
        assertEquals("VM Paused (Lifecycle Event)", record.rawMessage());
        assertEquals("vm paused (lifecycle event)", record.pattern());
        assertEquals("openstack-anomaly-vm", record.incidentFamily());
    }

    @Test
    void normalizesIdsNumbersAndIpsInPattern() {
        String line = "nova-api.log.2017-05-14_21:27:04 2017-05-14 19:39:01.445 25746 INFO "
                + "nova.osapi_compute.wsgi.server [req-abc - - - - -] 10.11.10.1 "
                + "\"GET /v2/54fadb412c4e40cdbaed9335e4c35a9e/servers/3edec1e4-9678-4a3a-a21b-a145a4ee5e61 HTTP/1.1\" "
                + "status: 200 len: 1583 time: 0.1919448";

        OpenStackLogRecord record = new OpenStackLogParser().parse(
                line,
                1,
                OpenStackSourceRole.NORMAL_BASELINE,
                Set.of()
        );

        assertTrue(record.pattern().contains("<ip>"));
        assertTrue(record.pattern().contains("<uuid>"));
        assertTrue(record.pattern().contains("<number>"));
        assertEquals("openstack-normal", record.incidentFamily());
    }

    @Test
    void acceptsHeaderOnlyOpenStackLines() {
        String line = "nova-compute.log.1.2017-05-16_13:55:31 2017-05-16 03:19:45.356 2931 ERROR "
                + "oslo_service.periodic_task";

        OpenStackLogRecord record = new OpenStackLogParser().parse(
                line,
                27202,
                OpenStackSourceRole.NORMAL_BASELINE,
                Set.of()
        );

        assertEquals("nova-compute.log.1.2017-05-16_13:55:31", record.sourceFile());
        assertEquals(Instant.parse("2017-05-16T03:19:45.356Z"), record.originalTimestamp());
        assertEquals("ERROR", record.level());
        assertEquals("oslo_service.periodic_task", record.service());
        assertEquals("<empty-message>", record.rawMessage());
        assertEquals("<empty-message>", record.pattern());
    }
}
