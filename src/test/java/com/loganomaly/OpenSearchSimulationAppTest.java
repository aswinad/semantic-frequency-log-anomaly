package com.loganomaly;

import com.loganomaly.app.OpenSearchSimulationApp;
import com.loganomaly.config.AppConfig;
import com.loganomaly.config.Dotenv;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchSimulationAppTest {
    @Test
    void invalidDatasetModeActionCombinationFailsClearly() throws Exception {
        Path file = Files.createTempFile("log-anomaly-invalid-mode", ".env");
        Files.writeString(file, """
                DATASET_MODE=synthetic
                DATASET_ACTION=index
                """);

        AppConfig config = AppConfig.load(Dotenv.load(file));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OpenSearchSimulationApp.dispatch(config)
        );
        assertTrue(error.getMessage().contains("Unsupported DATASET_MODE/DATASET_ACTION"));
    }
}
