package com.genersoft.iot.vmp.ai.bean;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAnalysisResult {
    private String analysisId;
    private String channelId;
    private String channelName;
    private String capturedAt;
    private String analyzedAt;
    private String model;
    private int imageWidth;
    private int imageHeight;
    private long elapsedMs;
    private String status;
    private String riskName;
    private String summary;
    private List<String> tips = new ArrayList<>();
    private List<AiDetection> detections = new ArrayList<>();
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
}
