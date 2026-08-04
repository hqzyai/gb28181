package com.genersoft.iot.vmp.ai.conf;

import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.service.AiHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AiDemoHistorySeeder implements ApplicationRunner {

    private static final List<DemoFrame> DEMO_FRAMES = List.of(
            new DemoFrame("demo-guilinyi-20260721103336", "01-hot-work-84.jpg", 1784, 852,
                    "2026-07-21T10:33:36+08:00", 15.02D, "动火可燃物", 0.84D),
            new DemoFrame("demo-guilinyi-20260721101855", "02-height-89.jpg", 1792, 858,
                    "2026-07-21T10:18:55+08:00", 14.18D, "高处作业未系安全绳", 0.89D),
            new DemoFrame("demo-guilinyi-20260721101923", "03-height-90.jpg", 1792, 858,
                    "2026-07-21T10:19:23+08:00", 42.37D, "高处作业未系安全绳", 0.90D),
            new DemoFrame("demo-guilinyi-20260721101942", "04-height-71.jpg", 1792, 858,
                    "2026-07-21T10:19:42+08:00", 61.56D, "高处作业未系安全绳", 0.71D),
            new DemoFrame("demo-guilinyi-20260721103350", "05-hot-work-75.jpg", 1784, 852,
                    "2026-07-21T10:33:50+08:00", 29.13D, "动火可燃物", 0.75D),
            new DemoFrame("demo-guilinyi-20260721101844", "06-height-88.jpg", 1792, 858,
                    "2026-07-21T10:18:44+08:00", 3.21D, "高处作业未系安全绳", 0.88D),
            new DemoFrame("demo-guilinyi-20260721103403", "07-hot-work-75.jpg", 1784, 852,
                    "2026-07-21T10:34:03+08:00", 42.26D, "动火可燃物", 0.75D),
            new DemoFrame("demo-guilinyi-20260721103419", "08-hot-work-80.jpg", 1784, 852,
                    "2026-07-21T10:34:19+08:00", 58.56D, "动火可燃物", 0.80D)
    );

    private final AiAnalysisConfig config;
    private final AiHistoryService historyService;

    public AiDemoHistorySeeder(AiAnalysisConfig config, AiHistoryService historyService) {
        this.config = config;
        this.historyService = historyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!config.isDemoEnabled()) {
            return;
        }
        Integer channelId = historyService.findChannelIdByExactName(config.getDemoChannelName());
        if (channelId == null) {
            log.warn("AI demo history channel was not found: {}", config.getDemoChannelName());
            return;
        }
        int inserted = 0;
        for (DemoFrame frame : DEMO_FRAMES) {
            ClassPathResource resource = new ClassPathResource("demo/ai-history/guilinyi/" + frame.fileName());
            try (InputStream input = resource.getInputStream()) {
                byte[] image = input.readAllBytes();
                if (historyService.seedDemo(
                        channelId, config.getDemoSiteName(), toResult(frame), image, "image/jpeg")) {
                    inserted++;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("无法读取归林驿 AI 演示截图: " + frame.fileName(), ex);
            }
        }
        log.info("AI demo history is ready, channel={}, inserted={}, total={}",
                config.getDemoChannelName(), inserted, DEMO_FRAMES.size());
    }

    private AiAnalysisResult toResult(DemoFrame frame) {
        boolean alert = frame.confidence() >= config.getAlertThreshold();
        AiAnalysisResult result = new AiAnalysisResult();
        result.setAnalysisId(frame.analysisId());
        result.setCapturedAt(frame.detectedAt());
        result.setAnalyzedAt(frame.detectedAt());
        result.setVideoTimeSeconds(frame.videoTimeSeconds());
        result.setModel("demo-history-v1");
        result.setImageWidth(frame.width());
        result.setImageHeight(frame.height());
        result.setElapsedMs(0L);
        result.setStatus(alert ? "自动告警" : "人工复核");
        result.setRiskName(frame.riskName());
        result.setConfidence(frame.confidence());
        result.setSummary(summary(frame.riskName(), alert));
        result.setTips(tips(frame.riskName()));
        result.setDetections(List.of());
        return result;
    }

    private static String summary(String riskName, boolean alert) {
        if ("动火可燃物".equals(riskName)) {
            return alert
                    ? "检测到动火作业区域附近存在可燃物，请立即核查处置。"
                    : "检测到动火作业区域附近疑似存在可燃物，需人工复核。";
        }
        return alert
                ? "检测到高处作业人员未系安全绳，请立即停止相关作业并核查。"
                : "检测到高处作业人员疑似未系安全绳，需人工复核。";
    }

    private static List<String> tips(String riskName) {
        if ("动火可燃物".equals(riskName)) {
            return List.of("清理或隔离动火点周边可燃物", "配置灭火器材并落实动火监护");
        }
        return List.of("立即停止相关高处作业", "确认安全带、安全绳及可靠挂点后再复工");
    }

    private record DemoFrame(
            String analysisId,
            String fileName,
            int width,
            int height,
            String detectedAt,
            double videoTimeSeconds,
            String riskName,
            double confidence
    ) {
    }
}
