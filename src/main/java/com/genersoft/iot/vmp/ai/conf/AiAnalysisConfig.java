package com.genersoft.iot.vmp.ai.conf;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 视频帧识别配置。API Key 仅允许通过服务端配置注入，禁止返回给前端。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai-analysis", ignoreInvalidFields = false)
public class AiAnalysisConfig {

    private boolean enabled = false;
    private String apiUrl = "";
    private String apiKey = "";
    private String model = "";
    private double temperature = 1.0D;
    private int maxOutputTokens = 2048;
    private int maxImageBytes = 3 * 1024 * 1024;
    private int maxProviderResponseBytes = 1024 * 1024;
    private int maxConcurrent = 2;
    private int minRequestIntervalSeconds = 3;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 180;
    private int defaultIntervalSeconds = 10;
    private double reviewThreshold = 0.60D;
    private double alertThreshold = 0.85D;

    public boolean isConfigured() {
        return enabled
                && apiUrl != null && !apiUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank()
                && temperature >= 0D && temperature <= 2D
                && maxOutputTokens >= 256 && maxOutputTokens <= 32768
                && maxImageBytes >= 128 * 1024 && maxImageBytes <= 10 * 1024 * 1024
                && maxProviderResponseBytes >= 64 * 1024 && maxProviderResponseBytes <= 4 * 1024 * 1024
                && maxConcurrent >= 1 && maxConcurrent <= 32
                && minRequestIntervalSeconds >= 1 && minRequestIntervalSeconds <= 3600
                && connectTimeoutSeconds >= 1 && connectTimeoutSeconds <= 120
                && readTimeoutSeconds >= 1 && readTimeoutSeconds <= 600
                && defaultIntervalSeconds >= minRequestIntervalSeconds && defaultIntervalSeconds <= 3600
                && reviewThreshold >= 0D && reviewThreshold <= 1D
                && alertThreshold >= reviewThreshold && alertThreshold <= 1D;
    }
}
