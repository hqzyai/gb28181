package com.genersoft.iot.vmp.ai.bean;

import lombok.Data;

@Data
public class AiDetection {
    private String type;
    private String classCode;
    private String kind;
    private String source;
    private String modelVersion;
    private boolean calibrated;
    private double confidence;
    private int x;
    private int y;
    private int width;
    private int height;
    private String status;
}
