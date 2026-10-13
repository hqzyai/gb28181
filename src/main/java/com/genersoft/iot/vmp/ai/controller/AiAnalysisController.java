package com.genersoft.iot.vmp.ai.controller;

import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.bean.AiFrameRequest;
import com.genersoft.iot.vmp.ai.bean.AiTaskRequest;
import com.genersoft.iot.vmp.ai.service.AiBackgroundTaskService;
import com.genersoft.iot.vmp.ai.bean.AiHistoryPage;
import com.genersoft.iot.vmp.ai.service.AiAnalysisService;
import com.genersoft.iot.vmp.ai.service.AiHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;

@Tag(name = "AI安全识别")
@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;
    private final AiHistoryService aiHistoryService;
    private final AiBackgroundTaskService tasks;

    public AiAnalysisController(AiAnalysisService aiAnalysisService, AiHistoryService aiHistoryService, AiBackgroundTaskService tasks) {
        this.aiAnalysisService = aiAnalysisService;
        this.aiHistoryService = aiHistoryService;
        this.tasks = tasks;
    }

    @GetMapping("/config")
    @Operation(summary = "获取 AI 识别公开配置状态")
    public Map<String, Object> config() {
        return aiAnalysisService.publicConfig();
    }

    @PostMapping("/analyze-frame")
    @Operation(summary = "识别 GB28181 实时视频抽帧")
    public AiAnalysisResult analyzeFrame(@RequestBody AiFrameRequest request, Principal principal) {
        String actor = principal == null ? "authenticated-user" : principal.getName();
        AiAnalysisResult result = aiAnalysisService.analyze(request, actor);
        return aiHistoryService.persistLive(request, result, actor);
    }

    @PostMapping("/tasks/start")
    public Map<String, Object> startTask(@RequestBody AiTaskRequest request, Principal principal) {
        return tasks.start(request, principal == null ? "authenticated-user" : principal.getName());
    }

    @PostMapping("/tasks/{channelId}/stop")
    public Map<String, Object> stopTask(@PathVariable int channelId) { return tasks.stop(channelId); }

    @GetMapping("/tasks/{channelId}")
    public Map<String, Object> taskStatus(@PathVariable int channelId) { return tasks.status(channelId); }

    @GetMapping("/tasks/{channelId}/latest")
    public Map<String, Object> taskLatest(@PathVariable int channelId) { return tasks.latest(channelId); }

    @GetMapping("/history")
    @Operation(summary = "按摄像头分页查询历史识别记录")
    public AiHistoryPage history(@RequestParam String channelId,
                                 @RequestParam(defaultValue = "1") int page) {
        return aiHistoryService.list(channelId, page);
    }

    @GetMapping("/history/{analysisId}/image")
    @Operation(summary = "读取历史识别截图")
    public ResponseEntity<Resource> historyImage(@PathVariable String analysisId) throws IOException {
        AiHistoryService.StoredHistoryImage image = aiHistoryService.getImage(analysisId);
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(image.contentType());
        } catch (IllegalArgumentException ex) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        FileSystemResource resource = new FileSystemResource(image.path());
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(resource.contentLength())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
