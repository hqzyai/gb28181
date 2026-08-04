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

    private static final List<DemoFrame> GUILINYI_FRAMES = List.of(
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

    private static final List<DemoFrame> UNIVERSITY_PHASE_TWO_SECTION_FOUR_FRAMES = List.of(
            new DemoFrame("demo-university-p2s4-20260711160734-65", "01-hoisting-no-hopper-65.jpg", 1920, 1080,
                    "2026-07-11T16:07:34+08:00", 34.00D, "吊运散料未使用料斗", 0.65D),
            new DemoFrame("demo-university-p2s4-20260711160739-70", "02-hoisting-no-hopper-70.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", 39.00D, "吊运散料未使用料斗", 0.70D),
            new DemoFrame("demo-university-p2s4-20260711160739-68", "03-hoisting-no-hopper-68.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", 39.20D, "吊运散料未使用料斗", 0.68D),
            new DemoFrame("demo-university-p2s4-20260711160739-72", "04-hoisting-no-hopper-72.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", 39.40D, "吊运散料未使用料斗", 0.72D)
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
        seedDataset(new DemoDataset(
                config.getDemoChannelName(),
                config.getDemoSiteName(),
                "demo/ai-history/guilinyi/",
                GUILINYI_FRAMES
        ));
        seedDataset(new DemoDataset(
                config.getDemoUniversityChannelName(),
                config.getDemoUniversitySiteName(),
                "demo/ai-history/university-phase2-section4/",
                UNIVERSITY_PHASE_TWO_SECTION_FOUR_FRAMES
        ));
    }

    private void seedDataset(DemoDataset dataset) {
        Integer channelId = historyService.findChannelIdByExactName(dataset.channelName());
        if (channelId == null) {
            log.warn("AI demo history channel was not found: {}", dataset.channelName());
            return;
        }
        int inserted = 0;
        for (DemoFrame frame : dataset.frames()) {
            ClassPathResource resource = new ClassPathResource(dataset.resourceDirectory() + frame.fileName());
            try (InputStream input = resource.getInputStream()) {
                byte[] image = input.readAllBytes();
                if (historyService.seedDemo(
                        channelId, dataset.siteName(), toResult(frame), image, "image/jpeg")) {
                    inserted++;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("无法读取 AI 演示截图: " + resource.getPath(), ex);
            }
        }
        log.info("AI demo history is ready, channel={}, inserted={}, total={}",
                dataset.channelName(), inserted, dataset.frames().size());
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
        if ("吊运散料未使用料斗".equals(riskName)) {
            return alert
                    ? "检测到吊运散料未使用料斗，存在散料坠落风险，请立即停止吊运并核查。"
                    : "检测到吊运散料疑似未使用料斗，存在散料坠落风险，需人工复核。";
        }
        return alert
                ? "检测到高处作业人员未系安全绳，请立即停止相关作业并核查。"
                : "检测到高处作业人员疑似未系安全绳，需人工复核。";
    }

    private static List<String> tips(String riskName) {
        if ("动火可燃物".equals(riskName)) {
            return List.of("清理或隔离动火点周边可燃物", "配置灭火器材并落实动火监护");
        }
        if ("吊运散料未使用料斗".equals(riskName)) {
            return List.of("立即停止相关吊运作业", "将散料装入合规料斗并确认防散落措施后再复工");
        }
        return List.of("立即停止相关高处作业", "确认安全带、安全绳及可靠挂点后再复工");
    }

    private record DemoDataset(
            String channelName,
            String siteName,
            String resourceDirectory,
            List<DemoFrame> frames
    ) {
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
