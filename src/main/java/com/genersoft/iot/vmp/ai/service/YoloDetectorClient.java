package com.genersoft.iot.vmp.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.ai.bean.AiDetection;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Internal authenticated detector API. No token, endpoint or raw provider errors leave this client. */
@Service
public class YoloDetectorClient {
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final Map<String, String> NEGATIVE_CLASSES = Map.of(
            "no_helmet", "未戴安全帽", "no_goggle", "未戴护目镜",
            "no_gloves", "未戴手套", "no_boots", "未穿安全靴");
    private static final Set<String> CLASSES = Set.of("helmet", "gloves", "vest", "boots", "goggles", "none", "Person", "no_helmet", "no_goggle", "no_gloves", "no_boots");
    private final AiAnalysisConfig config;
    private final OkHttpClient client;
    private volatile Map<String, Object> cachedModel = Map.of("ready", false);
    private volatile long checkedAt;

    public YoloDetectorClient(AiAnalysisConfig config) {
        this.config = config;
        this.client = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(Math.max(1, config.getYoloTimeoutSeconds()), TimeUnit.SECONDS)
                .callTimeout(Math.max(1, config.getYoloTimeoutSeconds()), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
    }

    public Map<String, Object> publicModel() {
        if (!config.isYoloConfigured()) return Map.of("ready", false, "reason", "YOLO 服务未配置");
        if (System.nanoTime() - checkedAt < TimeUnit.SECONDS.toNanos(5)) return cachedModel;
        synchronized (this) {
            if (System.nanoTime() - checkedAt < TimeUnit.SECONDS.toNanos(5)) return cachedModel;
            try {
                JSONObject ready = call("/readyz", null);
                JSONObject model = call("/v1/model", null);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ready", "ready".equals(ready.getString("status")));
                // Explicit allowlist prevents accidental exposure of future internal fields.
                for (String field : List.of("modelName", "modelVersion", "sha256", "classes", "capabilities"))
                    data.put(field, model.get(field));
                cachedModel = data;
            } catch (ControllerException ex) {
                cachedModel = Map.of("ready", false, "reason", ex.getMsg());
            }
            checkedAt = System.nanoTime();
            return cachedModel;
        }
    }

    public JSONObject detect(String imageData, String frameId, String capturedAt) {
        if (!config.isYoloConfigured())
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "YOLO 服务未配置或未启用");
        JSONObject request = new JSONObject();
        request.put("imageData", imageData);
        request.put("frameId", frameId);
        request.put("capturedAt", capturedAt);
        request.put("confidence", config.getYoloConfidence());
        return call("/v1/detect", request);
    }

    private JSONObject call(String path, JSONObject payload) {
        String url = config.getYoloUrl().replaceAll("/+$", "") + path;
        Request.Builder builder = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + config.getYoloToken());
        if (payload != null) builder.post(RequestBody.create(payload.toJSONString(), JSON_TYPE));
        // No automatic retry: retrying a completed inference would duplicate work under load.
        try (Response response = (payload == null ? client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build() : client).newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                int code = response.code() == 429 ? ErrorCode.ERROR486.getCode() : ErrorCode.ERROR100.getCode();
                throw new ControllerException(code, "YOLO 检测服务不可用（HTTP " + response.code() + "），本帧未完成识别");
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("empty detector response");
            long limit = config.getMaxProviderResponseBytes();
            if (body.contentLength() > limit) throw new IOException("response too large");
            body.source().request(limit + 1);
            if (body.source().getBuffer().size() > limit) throw new IOException("response too large");
            JSONObject parsed = JSON.parseObject(body.source().getBuffer().clone().readUtf8());
            if (parsed == null) throw new IOException("empty JSON");
            return parsed;
        } catch (ControllerException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ControllerException(ErrorCode.ERROR408.getCode(), "YOLO 连接超时或响应无效，本帧未完成识别");
        }
    }

    static List<AiDetection> parseBoxes(JSONObject response, int width, int height, double threshold) {
        if (response == null || !"yolo".equals(response.getString("engine"))
                || response.getIntValue("imageWidth") != width || response.getIntValue("imageHeight") != height
                || response.getString("modelVersion") == null || response.getString("modelVersion").isBlank()
                || !(response.get("boxes") instanceof JSONArray))
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "YOLO 返回的模型、尺寸或目标格式无效，本帧未完成识别");
        List<AiDetection> result = new ArrayList<>();
        for (Object raw : response.getJSONArray("boxes")) {
            if (!(raw instanceof JSONObject item)) continue;
            String code = item.getString("classCode");
            if (!CLASSES.contains(code == null ? "" : code))
                throw new ControllerException(ErrorCode.ERROR100.getCode(), "YOLO 返回未知 PPE 类别，本帧未完成识别");
            double confidence = item.getDoubleValue("confidence");
            double x1 = item.getDoubleValue("x1"), y1 = item.getDoubleValue("y1");
            double x2 = item.getDoubleValue("x2"), y2 = item.getDoubleValue("y2");
            if (code == null || code.isBlank() || !Double.isFinite(confidence) || confidence < threshold || confidence > 1
                    || !Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)
                    || x2 <= x1 || y2 <= y1) continue;
            int x = Math.max(0, Math.min(width - 1, (int) Math.round(x1)));
            int y = Math.max(0, Math.min(height - 1, (int) Math.round(y1)));
            int boxWidth = Math.min(width - x, (int) Math.round(x2) - x);
            int boxHeight = Math.min(height - y, (int) Math.round(y2) - y);
            if (boxWidth < 2 || boxHeight < 2) continue;
            boolean risk = NEGATIVE_CLASSES.containsKey(code);
            AiDetection detection = new AiDetection();
            detection.setClassCode(code);
            detection.setKind(risk ? "risk" : "none".equals(code) ? "unknown" : "object");
            String label = item.getString("label");
            detection.setType(risk ? NEGATIVE_CLASSES.get(code) : label == null || label.isBlank() ? code : label);
            detection.setConfidence(Math.round(confidence * 10000D) / 10000D);
            detection.setX(x); detection.setY(y); detection.setWidth(boxWidth); detection.setHeight(boxHeight);
            detection.setStatus(risk ? "人工复核" : "目标检测");
            detection.setSource("yolo"); detection.setModelVersion(response.getString("modelVersion"));
            detection.setCalibrated(false);
            result.add(detection);
        }
        result.sort(Comparator.comparingDouble(AiDetection::getConfidence).reversed());
        return result;
    }
}
