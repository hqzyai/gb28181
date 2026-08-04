package com.genersoft.iot.vmp.ai.controller;

import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.bean.AiFrameRequest;
import com.genersoft.iot.vmp.ai.service.AiAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@Tag(name = "AI安全识别")
@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @GetMapping("/config")
    @Operation(summary = "获取 AI 识别公开配置状态")
    public Map<String, Object> config() {
        return aiAnalysisService.publicConfig();
    }

    @PostMapping("/analyze-frame")
    @Operation(summary = "识别 GB28181 实时视频抽帧")
    public AiAnalysisResult analyzeFrame(@RequestBody AiFrameRequest request, Principal principal) {
        return aiAnalysisService.analyze(request, principal == null ? "authenticated-user" : principal.getName());
    }
}
