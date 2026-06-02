package com.loganomaly;

import com.loganomaly.loghub.BglLogParser;
import com.loganomaly.loghub.BglLogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglLogParserTest {
    @Test
    void parsesBglLineAndPreservesNativeLabel() {
        String line = "APPREAD 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-15.42.50.363779 "
                + "R02-M1-N0-C:J12-U11 RAS KERNEL INFO ciod: failed to read message prefix on control stream "
                + "(ciostream socket to 10.0.0.1:1234)";

        BglLogRecord record = new BglLogParser().parse(line, 1);

        assertEquals("APPREAD", record.rawLabel());
        assertTrue(record.anomaly());
        assertEquals(Instant.parse("2005-06-03T15:42:50.363779Z"), record.timestamp());
        assertTrue(record.service().contains("R02-M1-N0-C:J12-U11"));
        assertTrue(record.pattern().contains("<ip>"));
        assertTrue(record.pattern().contains("<n>"));
    }

    @Test
    void dashLabelIsNormal() {
        String line = "- 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-15.42.50.363779 "
                + "R02-M1-N0-C:J12-U11 RAS KERNEL INFO instruction cache parity error corrected";

        BglLogRecord record = new BglLogParser().parse(line, 2);

        assertEquals("-", record.rawLabel());
        assertFalse(record.anomaly());
        assertEquals("instruction cache parity error corrected", record.pattern());
    }
    @Test
    void parsesSeverityOnlyBglLine() {
        String line = "- 1120866514 2005.07.08 R02-M1-N8-C:J13-U01 2005-07-08-16.48.34.004234 "
                + "R02-M1-N8-C:J13-U01 RAS KERNEL FATAL";

        BglLogRecord record = new BglLogParser().parse(line, 1_579_029);

        assertEquals("-", record.rawLabel());
        assertFalse(record.anomaly());
        assertEquals(Instant.parse("2005-07-08T16:48:34.004234Z"), record.timestamp());
        assertEquals("FATAL", record.rawMessage());
        assertEquals("fatal", record.pattern());
    }

}
