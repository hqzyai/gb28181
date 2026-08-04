package com.genersoft.iot.vmp.ai.conf;

import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.bean.AiChannelContext;
import com.genersoft.iot.vmp.ai.service.AiHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AiDemoHistorySeeder implements ApplicationRunner {

    private static final List<DemoFrame> GUILINYI_FRAMES = List.of(
            new DemoFrame("demo-guilinyi-20260721103336", "01-hot-work-84.jpg", 1784, 852,
                    "2026-07-21T10:33:36+08:00", "动火可燃物", 0.84D),
            new DemoFrame("demo-guilinyi-20260721101855", "02-height-89.jpg", 1792, 858,
                    "2026-07-21T10:18:55+08:00", "高处作业未系安全绳", 0.89D),
            new DemoFrame("demo-guilinyi-20260721101923", "03-height-90.jpg", 1792, 858,
                    "2026-07-21T10:19:23+08:00", "高处作业未系安全绳", 0.90D),
            new DemoFrame("demo-guilinyi-20260721101942", "04-height-71.jpg", 1792, 858,
                    "2026-07-21T10:19:42+08:00", "高处作业未系安全绳", 0.71D),
            new DemoFrame("demo-guilinyi-20260721103350", "05-hot-work-75.jpg", 1784, 852,
                    "2026-07-21T10:33:50+08:00", "动火可燃物", 0.75D),
            new DemoFrame("demo-guilinyi-20260721101844", "06-height-88.jpg", 1792, 858,
                    "2026-07-21T10:18:44+08:00", "高处作业未系安全绳", 0.88D),
            new DemoFrame("demo-guilinyi-20260721103403", "07-hot-work-75.jpg", 1784, 852,
                    "2026-07-21T10:34:03+08:00", "动火可燃物", 0.75D),
            new DemoFrame("demo-guilinyi-20260721103419", "08-hot-work-80.jpg", 1784, 852,
                    "2026-07-21T10:34:19+08:00", "动火可燃物", 0.80D)
    );

    private static final List<DemoFrame> UNIVERSITY_PHASE_TWO_SECTION_FOUR_FRAMES = List.of(
            new DemoFrame("demo-university-p2s4-20260711160734-65", "01-hoisting-no-hopper-65.jpg", 1920, 1080,
                    "2026-07-11T16:07:34+08:00", "吊运散料未使用料斗", 0.65D),
            new DemoFrame("demo-university-p2s4-20260711160739-70", "02-hoisting-no-hopper-70.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", "吊运散料未使用料斗", 0.70D),
            new DemoFrame("demo-university-p2s4-20260711160739-68", "03-hoisting-no-hopper-68.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", "吊运散料未使用料斗", 0.68D),
            new DemoFrame("demo-university-p2s4-20260711160739-72", "04-hoisting-no-hopper-72.jpg", 1920, 1080,
                    "2026-07-11T16:07:39+08:00", "吊运散料未使用料斗", 0.72D)
    );

    private static final List<DemoFrame> QINGTENG_TOWN_FRAMES = List.of(
            new DemoFrame("demo-qingteng-20260714150822-68", "01-edge-no-guard-68.jpg", 1280, 688,
                    "2026-07-14T15:08:22+08:00", "高处临边无防护", 0.68D),
            new DemoFrame("demo-qingteng-20260714150835-67", "02-edge-no-guard-67.jpg", 1280, 688,
                    "2026-07-14T15:08:35+08:00", "高处临边无防护", 0.67D),
            new DemoFrame("demo-qingteng-20260714150848-79", "03-edge-no-guard-79.jpg", 1280, 688,
                    "2026-07-14T15:08:48+08:00", "高处临边无防护", 0.79D),
            new DemoFrame("demo-qingteng-20260714150902-66", "04-edge-no-guard-66.jpg", 1280, 688,
                    "2026-07-14T15:09:02+08:00", "高处临边无防护", 0.66D),
            new DemoFrame("demo-qingteng-20260714145338-88", "05-height-no-lifeline-88.jpg", 2026, 1080,
                    "2026-07-14T14:53:38+08:00", "高处作业未系安全绳", 0.88D),
            new DemoFrame("demo-qingteng-20260714145404-69", "06-height-no-lifeline-69.jpg", 2026, 1080,
                    "2026-07-14T14:54:04+08:00", "高处作业未系安全绳", 0.69D),
            new DemoFrame("demo-qingteng-20260714145351-80", "07-height-no-lifeline-80.jpg", 2026, 1080,
                    "2026-07-14T14:53:51+08:00", "高处作业未系安全绳", 0.80D),
            new DemoFrame("demo-qingteng-20260714145417-88", "08-height-no-lifeline-88.jpg", 2026, 1080,
                    "2026-07-14T14:54:17+08:00", "高处作业未系安全绳", 0.88D)
    );

    private final AiAnalysisConfig config;
    private final AiHistoryService historyService;
    private final Map<String, byte[]> imageCache = new ConcurrentHashMap<>();

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
        reconcileGuilinyiSynchronizedDemoHistory();
        reconcileQingtengDemoHistory();
    }

    @Scheduled(
            initialDelayString = "${ai-analysis.demo-guilinyi-sync-reconcile-interval-ms:300000}",
            fixedDelayString = "${ai-analysis.demo-guilinyi-sync-reconcile-interval-ms:300000}"
    )
    public synchronized void reconcileGuilinyiSynchronizedDemoHistory() {
        if (!config.isDemoEnabled()) {
            return;
        }
        seedMatchingDataset(new MatchingDemoDataset(
                parseChannelNameParts(config.getDemoGuilinyiSyncChannelNameContains()),
                config.getDemoSiteName(),
                "demo/ai-history/guilinyi/",
                GUILINYI_FRAMES
        ));
    }

    @Scheduled(
            initialDelayString = "${ai-analysis.demo-qingteng-reconcile-interval-ms:300000}",
            fixedDelayString = "${ai-analysis.demo-qingteng-reconcile-interval-ms:300000}"
    )
    public synchronized void reconcileQingtengDemoHistory() {
        if (!config.isDemoEnabled()) {
            return;
        }
        seedMatchingDataset(new MatchingDemoDataset(
                parseChannelNameParts(config.getDemoQingtengChannelNameContains()),
                config.getDemoQingtengSiteName(),
                "demo/ai-history/qingteng-town/",
                QINGTENG_TOWN_FRAMES
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
            if (historyService.seedDemo(
                    channelId, dataset.siteName(), toResult(frame, frame.analysisId()),
                    loadImage(dataset.resourceDirectory(), frame), "image/jpeg")) {
                inserted++;
            }
        }
        log.info("AI demo history is ready, channel={}, inserted={}, total={}",
                dataset.channelName(), inserted, dataset.frames().size());
    }

    private void seedMatchingDataset(MatchingDemoDataset dataset) {
        Map<Integer, AiChannelContext> matchedChannels = new LinkedHashMap<>();
        for (String channelNamePart : dataset.channelNameParts()) {
            if (channelNamePart == null || channelNamePart.isBlank()) {
                continue;
            }
            List<AiChannelContext> channels = historyService.findChannelsByNameContaining(channelNamePart.trim());
            if (channels == null) {
                continue;
            }
            for (AiChannelContext channel : channels) {
                if (channel != null && channel.getChannelId() > 0) {
                    matchedChannels.putIfAbsent(channel.getChannelId(), channel);
                }
            }
        }
        if (matchedChannels.isEmpty()) {
            log.debug("AI matching demo history camera was not found: {}", dataset.channelNameParts());
            return;
        }
        for (AiChannelContext channel : matchedChannels.values()) {
            int inserted = 0;
            for (DemoFrame frame : dataset.frames()) {
                String analysisId = frame.analysisId() + "-c" + channel.getChannelId();
                if (historyService.seedDemo(
                        channel.getChannelId(), dataset.siteName(), toResult(frame, analysisId),
                        loadImage(dataset.resourceDirectory(), frame), "image/jpeg")) {
                    inserted++;
                }
            }
            if (inserted > 0) {
                log.info("AI matching demo history is ready, channel={}, channelId={}, inserted={}, total={}",
                        channel.getChannelName(), channel.getChannelId(), inserted, dataset.frames().size());
            } else {
                log.debug("AI matching demo history is already complete, channel={}, channelId={}, total={}",
                        channel.getChannelName(), channel.getChannelId(), dataset.frames().size());
            }
        }
    }

    private static List<String> parseChannelNameParts(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawValue.split("[,，;；/|\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private byte[] loadImage(String resourceDirectory, DemoFrame frame) {
        String path = resourceDirectory + frame.fileName();
        return imageCache.computeIfAbsent(path, this::readImage);
    }

    private byte[] readImage(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取 AI 演示截图: " + resource.getPath(), ex);
        }
    }

    private AiAnalysisResult toResult(DemoFrame frame, String analysisId) {
        boolean alert = frame.confidence() >= config.getAlertThreshold();
        AiAnalysisResult result = new AiAnalysisResult();
        result.setAnalysisId(analysisId);
        result.setCapturedAt(frame.detectedAt());
        result.setAnalyzedAt(frame.detectedAt());
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
        if ("高处临边无防护".equals(riskName)) {
            return alert
                    ? "检测到高处临边缺少有效防护，请立即封闭危险区域并核查。"
                    : "检测到高处临边疑似缺少有效防护，需人工复核。";
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
        if ("高处临边无防护".equals(riskName)) {
            return List.of("立即封闭临边危险区域", "按规范设置防护栏杆、挡脚板和警示标识并验收");
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

    private record MatchingDemoDataset(
            List<String> channelNameParts,
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
            String riskName,
            double confidence
    ) {
    }
}
