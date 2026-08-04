package com.genersoft.iot.vmp.ai.service;

import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiHistoryStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesImagesUnderConfiguredRootAndRejectsTraversal() throws Exception {
        AiAnalysisConfig config = new AiAnalysisConfig();
        config.setHistoryDirectory(temporaryDirectory.toString());
        AiHistoryStorage storage = new AiHistoryStorage(config);
        storage.initialize();

        byte[] image = {1, 2, 3, 4};
        AiHistoryStorage.StoredImage stored = storage.store(
                35, "history-test-001", image, "image/jpeg", LocalDate.of(2026, 7, 21));

        assertTrue(stored.path().startsWith("35/2026/07/21/"));
        Path resolved = storage.resolveExisting(stored.path());
        assertNotNull(resolved);
        assertArrayEquals(image, Files.readAllBytes(resolved));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveExisting("../../etc/passwd"));
    }
}
