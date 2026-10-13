package com.genersoft.iot.vmp.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.ai.bean.*;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YoloDetectorClientTest {
    static JSONObject response(String boxes) {
        return JSON.parseObject("{\"engine\":\"yolo\",\"modelVersion\":\"ppe-test\",\"modelSha256\":\"abc\",\"imageWidth\":100,\"imageHeight\":100,\"boxes\":" + boxes + "}");
    }
    @Test void positivePpeIsNotAnAlertAndNegativeIsAlwaysReview() {
        List<AiDetection> detections = YoloDetectorClient.parseBoxes(response("["
                + "{\"classCode\":\"helmet\",\"label\":\"安全帽\",\"confidence\":0.99,\"x1\":10,\"y1\":10,\"x2\":30,\"y2\":30},"
                + "{\"classCode\":\"no_helmet\",\"confidence\":0.98,\"x1\":40,\"y1\":10,\"x2\":60,\"y2\":30}]"), 100, 100, .35);
        assertEquals("object", detections.get(0).getKind());
        assertEquals("目标检测", detections.get(0).getStatus());
        assertEquals("risk", detections.get(1).getKind());
        assertEquals("人工复核", detections.get(1).getStatus());
        assertEquals("未戴安全帽", detections.get(1).getType());
        assertFalse(detections.get(1).isCalibrated());
    }
    @Test void unsupportedNoVestAndCoordinateMismatchFailInsteadOfReportingNormal() {
        assertThrows(ControllerException.class, () -> YoloDetectorClient.parseBoxes(response("[]"), 101, 100, .35));
        assertThrows(ControllerException.class, () -> YoloDetectorClient.parseBoxes(response("[{\"classCode\":\"no_vest\"}]"), 100, 100, .35));
    }
    @Test void thresholdAndBoundsAreAppliedInOriginalPixels() {
        List<AiDetection> boxes = YoloDetectorClient.parseBoxes(response("["
                + "{\"classCode\":\"Person\",\"confidence\":0.8,\"x1\":90,\"y1\":90,\"x2\":130,\"y2\":150},"
                + "{\"classCode\":\"vest\",\"confidence\":0.34,\"x1\":1,\"y1\":1,\"x2\":20,\"y2\":20}]"), 100, 100, .35);
        assertEquals(1, boxes.size()); assertEquals(10, boxes.get(0).getWidth()); assertEquals(10, boxes.get(0).getHeight());
    }
    @Test void yoloModeDoesNotContactVisionAndPublicConfigOmitsSecrets() throws Exception {
        AtomicInteger visionCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vision", exchange -> { visionCalls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/v1/detect", exchange -> {
            assertEquals("Bearer private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(request.getString("imageData").startsWith("data:image/jpeg;base64,"));
            byte[] payload = response("[]").toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.createContext("/readyz", exchange -> {
            byte[] payload = "{\"status\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.createContext("/v1/model", exchange -> {
            byte[] payload = "{\"modelName\":\"ppe\",\"modelVersion\":\"ppe-test\",\"token\":\"must-not-leak\",\"classes\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.start();
        try {
            AiAnalysisConfig config = new AiAnalysisConfig(); config.setEnabled(true); config.setYoloEnabled(true);
            config.setYoloUrl("http://127.0.0.1:" + server.getAddress().getPort()); config.setYoloToken("private-token");
            config.setApiUrl(config.getYoloUrl() + "/vision"); config.setApiKey("private-vision"); config.setModel("qwen-test");
            AiAnalysisService service = new AiAnalysisService(config, new YoloDetectorClient(config));
            AiFrameRequest request = new AiFrameRequest(); request.setEngine("yolo"); request.setImageWidth(100); request.setImageHeight(100);
            request.setImageData("data:image/jpeg;base64,/9j/AA=="); request.setCapturedAt("2026-10-12T12:00:00+08:00");
            AiAnalysisResult result = service.analyze(request, "test");
            assertEquals("yolo", result.getEngine()); assertEquals("ppe-test", result.getModel()); assertEquals(0, visionCalls.get());
            assertEquals(0, result.getTotalTokens());
            String publicConfig = JSON.toJSONString(service.publicConfig());
            assertFalse(publicConfig.contains("private-token")); assertFalse(publicConfig.contains("private-vision")); assertFalse(publicConfig.contains("must-not-leak"));
        } finally { server.stop(0); }
    }
    @Test void detectorBusyIsExplicitFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/detect", exchange -> { exchange.sendResponseHeaders(429, -1); exchange.close(); });
        server.start();
        try {
            AiAnalysisConfig config = new AiAnalysisConfig(); config.setEnabled(true); config.setYoloEnabled(true);
            config.setYoloUrl("http://127.0.0.1:" + server.getAddress().getPort()); config.setYoloToken("secret");
            ControllerException ex = assertThrows(ControllerException.class, () -> new YoloDetectorClient(config).detect("x", "f", "time"));
            assertEquals(486, ex.getCode()); assertTrue(ex.getMsg().contains("未完成识别"));
        } finally { server.stop(0); }
    }
}
