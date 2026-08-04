package com.genersoft.iot.vmp.ai.bean;

import lombok.Data;

@Data
public class AiFrameRequest {
    private String channelId;
    private String channelName;
    private String imageData;
    private Integer imageWidth;
    private Integer imageHeight;
    private String capturedAt;
    private Double videoTimeSeconds;
}
