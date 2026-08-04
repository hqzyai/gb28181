package com.genersoft.iot.vmp.ai.bean;

import lombok.Data;

@Data
public class AiDetection {
    private String type;
    private double confidence;
    private int x;
    private int y;
    private int width;
    private int height;
    private String status;
}
