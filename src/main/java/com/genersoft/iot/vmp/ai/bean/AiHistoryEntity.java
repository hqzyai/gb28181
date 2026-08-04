package com.genersoft.iot.vmp.ai.bean;

import lombok.Data;

@Data
public class AiHistoryEntity {
    private String analysisId;
    private int channelId;
    private String channelDeviceId;
    private String channelName;
    private String siteName;
    private String capturedAt;
    private String analyzedAt;
    private Double videoTimeSeconds;
    private String model;
    private int imageWidth;
    private int imageHeight;
    private String imagePath;
    private String imageContentType;
    private long elapsedMs;
    private String status;
    private String riskName;
    private double confidence;
    private String summary;
    private String tipsJson;
    private String detectionsJson;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private String recordSource;
    private String createdBy;
    private String createTime;
}
