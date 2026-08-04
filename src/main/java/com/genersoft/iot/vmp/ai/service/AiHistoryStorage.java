package com.genersoft.iot.vmp.ai.service;

import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

@Slf4j
@Component
public class AiHistoryStorage {

    private final AiAnalysisConfig config;
    private Path root;

    public AiHistoryStorage(AiAnalysisConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void initialize() {
        root = Path.of(config.getHistoryDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法初始化 AI 历史图片目录: " + root, ex);
        }
        log.info("AI history image directory: {}", root);
    }

    public StoredImage store(int channelId, String analysisId, byte[] content, String contentType, LocalDate date) {
        if (channelId <= 0 || analysisId == null || !analysisId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("AI 历史图片标识无效");
        }
        if (content == null || content.length == 0 || content.length > config.getMaxImageBytes()) {
            throw new IllegalArgumentException("AI 历史图片大小无效");
        }
        String extension = "image/png".equalsIgnoreCase(contentType) ? ".png" : ".jpg";
        LocalDate safeDate = date == null ? LocalDate.now() : date;
        Path relative = Path.of(
                Integer.toString(channelId),
                Integer.toString(safeDate.getYear()),
                String.format("%02d", safeDate.getMonthValue()),
                String.format("%02d", safeDate.getDayOfMonth()),
                analysisId + extension
        );
        Path target = resolveSafe(relative.toString());
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".ai-history-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String storedPath = root.relativize(target).toString().replace('\\', '/');
            return new StoredImage(storedPath, extension.equals(".png") ? "image/png" : "image/jpeg");
        } catch (IOException ex) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    log.warn("Failed to clean temporary AI history image: {}", temporary, cleanupError);
                }
            }
            throw new IllegalStateException("保存 AI 历史图片失败", ex);
        }
    }

    public Path resolveExisting(String storedPath) {
        Path path = resolveSafe(storedPath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return null;
        }
        return path;
    }

    public void deleteQuietly(String storedPath) {
        try {
            Files.deleteIfExists(resolveSafe(storedPath));
        } catch (RuntimeException | IOException ex) {
            log.warn("Failed to remove orphan AI history image: {}", storedPath, ex);
        }
    }

    private Path resolveSafe(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("AI 历史图片路径为空");
        }
        Path path = root.resolve(storedPath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("AI 历史图片路径无效");
        }
        return path;
    }

    public record StoredImage(String path, String contentType) {
    }
}
