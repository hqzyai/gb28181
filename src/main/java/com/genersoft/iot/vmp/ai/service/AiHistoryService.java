package com.genersoft.iot.vmp.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.bean.AiChannelContext;
import com.genersoft.iot.vmp.ai.bean.AiDetection;
import com.genersoft.iot.vmp.ai.bean.AiFrameRequest;
import com.genersoft.iot.vmp.ai.bean.AiHistoryEntity;
import com.genersoft.iot.vmp.ai.bean.AiHistoryPage;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.ai.dao.AiHistoryMapper;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class AiHistoryService {

    public static final int PAGE_SIZE = 10;
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final AiHistoryMapper mapper;
    private final AiHistoryStorage storage;
    private final AiAnalysisConfig config;

    public AiHistoryService(AiHistoryMapper mapper, AiHistoryStorage storage, AiAnalysisConfig config) {
        this.mapper = mapper;
        this.storage = storage;
        this.config = config;
    }

    public AiAnalysisResult persistLive(AiFrameRequest request, AiAnalysisResult result, String actor) {
        int channelId = parseChannelId(request == null ? null : request.getChannelId());
        AiChannelContext channel = requireChannel(channelId);
        ImagePayload image = decodeDataUrl(request.getImageData());
        applyChannelContext(result, channel, null);
        if (!"BACKGROUND".equals(result.getSource())) result.setSource("LIVE");
        return persist(result, image.bytes(), image.contentType(), actor);
    }

    public AiHistoryPage list(String rawChannelId, int page) {
        int channelId = parseChannelId(rawChannelId);
        requireChannel(channelId);
        if (page < 1) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "页码必须大于 0");
        }
        long total = mapper.countByChannelId(channelId);
        long offset = (long) (page - 1) * PAGE_SIZE;
        List<AiAnalysisResult> list = offset >= total
                ? List.of()
                : mapper.findPage(channelId, PAGE_SIZE, offset).stream().map(this::toResult).toList();
        int pages = total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        return new AiHistoryPage(list, total, page, PAGE_SIZE, pages);
    }

    public StoredHistoryImage getImage(String analysisId) {
        String safeId = validateAnalysisId(analysisId);
        AiHistoryEntity entity = mapper.findByAnalysisId(safeId);
        if (entity == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "识别记录不存在");
        }
        Path image = storage.resolveExisting(entity.getImagePath());
        if (image == null) {
            log.error("AI history image is missing, analysisId={}, path={}", safeId, entity.getImagePath());
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "识别记录图片不存在");
        }
        return new StoredHistoryImage(image, entity.getImageContentType());
    }

    public Integer findChannelIdByExactName(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        AiChannelContext channel = mapper.findChannelByExactName(channelName.trim());
        return channel == null ? null : channel.getChannelId();
    }

    public List<AiChannelContext> findChannelsByNameContaining(String channelNamePart) {
        if (channelNamePart == null || channelNamePart.isBlank()) {
            return List.of();
        }
        List<AiChannelContext> channels = mapper.findChannelsByNameContaining(channelNamePart.trim());
        return channels == null ? List.of() : List.copyOf(channels);
    }

    public boolean seedDemo(int channelId, String siteName, AiAnalysisResult result, byte[] image, String contentType) {
        if (mapper.countByAnalysisId(validateAnalysisId(result.getAnalysisId())) > 0) {
            return false;
        }
        AiChannelContext channel = requireChannel(channelId);
        applyChannelContext(result, channel, siteName);
        result.setSource("DEMO");
        persist(result, image, contentType, "system-demo");
        return true;
    }

    private AiAnalysisResult persist(AiAnalysisResult result, byte[] image, String contentType, String actor) {
        LocalDate recordDate = recordDate(result.getAnalyzedAt());
        AiHistoryStorage.StoredImage stored = storage.store(
                Integer.parseInt(result.getChannelId()), result.getAnalysisId(), image, contentType, recordDate);
        AiHistoryEntity entity = toEntity(result, stored, actor);
        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("AI 历史记录写入数量异常");
            }
        } catch (RuntimeException ex) {
            storage.deleteQuietly(stored.path());
            throw ex;
        }
        result.setImageUrl(imageUrl(result.getAnalysisId()));
        return result;
    }

    private AiHistoryEntity toEntity(
            AiAnalysisResult result,
            AiHistoryStorage.StoredImage stored,
            String actor
    ) {
        AiHistoryEntity entity = new AiHistoryEntity();
        entity.setAnalysisId(validateAnalysisId(result.getAnalysisId()));
        entity.setChannelId(parseChannelId(result.getChannelId()));
        entity.setChannelDeviceId(required(result.getChannelDeviceId(), 128, "通道编码"));
        entity.setChannelName(required(result.getChannelName(), 255, "通道名称"));
        entity.setSiteName(required(result.getSiteName(), 255, "工地名称"));
        entity.setCapturedAt(required(orElse(result.getCapturedAt(), result.getAnalyzedAt()), 64, "抓拍时间"));
        entity.setAnalyzedAt(required(result.getAnalyzedAt(), 64, "检测时间"));
        // AI history intentionally does not retain source-video position metadata.
        entity.setVideoTimeSeconds(null);
        entity.setModel(optional(result.getModel(), 128));
        entity.setImageWidth(Math.max(1, result.getImageWidth()));
        entity.setImageHeight(Math.max(1, result.getImageHeight()));
        entity.setImagePath(stored.path());
        entity.setImageContentType(stored.contentType());
        entity.setElapsedMs(Math.max(0, result.getElapsedMs()));
        entity.setStatus(required(result.getStatus(), 32, "处理状态"));
        entity.setRiskName(required(result.getRiskName(), 255, "风险类型"));
        entity.setConfidence(clampConfidence(result.getConfidence()));
        entity.setSummary(optional(result.getSummary(), 10_000));
        entity.setTipsJson(JSON.toJSONString(result.getTips() == null ? List.of() : result.getTips()));
        JSONObject envelope = new JSONObject();
        envelope.put("schemaVersion", 2);
        envelope.put("engine", result.getEngine());
        envelope.put("modelVersion", result.getModelVersion());
        envelope.put("modelSha256", result.getModelSha256());
        envelope.put("frameId", result.getFrameId());
        envelope.put("detections", result.getDetections() == null ? List.of() : result.getDetections());
        entity.setDetectionsJson(envelope.toJSONString());
        entity.setInputTokens(Math.max(0, result.getInputTokens()));
        entity.setOutputTokens(Math.max(0, result.getOutputTokens()));
        entity.setTotalTokens(Math.max(0, result.getTotalTokens()));
        entity.setRecordSource(required(result.getSource(), 32, "记录来源"));
        entity.setCreatedBy(optional(actor, 200).isBlank() ? "authenticated-user" : optional(actor, 200));
        entity.setCreateTime(OffsetDateTime.now(CHINA_ZONE).toString());
        return entity;
    }

    private AiAnalysisResult toResult(AiHistoryEntity entity) {
        AiAnalysisResult result = new AiAnalysisResult();
        result.setAnalysisId(entity.getAnalysisId());
        result.setChannelId(Integer.toString(entity.getChannelId()));
        result.setChannelDeviceId(entity.getChannelDeviceId());
        result.setChannelName(entity.getChannelName());
        result.setSiteName(entity.getSiteName());
        result.setCapturedAt(entity.getCapturedAt());
        result.setAnalyzedAt(entity.getAnalyzedAt());
        result.setVideoTimeSeconds(null);
        result.setModel(entity.getModel());
        result.setImageWidth(entity.getImageWidth());
        result.setImageHeight(entity.getImageHeight());
        result.setElapsedMs(entity.getElapsedMs());
        result.setStatus(entity.getStatus());
        result.setRiskName(entity.getRiskName());
        result.setConfidence(entity.getConfidence());
        result.setSummary(entity.getSummary());
        result.setTips(parseTips(entity.getTipsJson()));
        result.setDetections(parseDetections(entity.getDetectionsJson()));
        try {
            Object payload = JSON.parse(entity.getDetectionsJson());
            if (payload instanceof JSONObject envelope) {
                result.setEngine(envelope.getString("engine"));
                result.setModelVersion(envelope.getString("modelVersion"));
                result.setModelSha256(envelope.getString("modelSha256"));
                result.setFrameId(envelope.getString("frameId"));
            } else {
                result.setEngine("vision"); result.setModelVersion(entity.getModel());
            }
        } catch (RuntimeException ignored) { result.setEngine("vision"); }
        result.setInputTokens(entity.getInputTokens());
        result.setOutputTokens(entity.getOutputTokens());
        result.setTotalTokens(entity.getTotalTokens());
        result.setSource(entity.getRecordSource());
        result.setImageUrl(imageUrl(entity.getAnalysisId()));
        return result;
    }

    private List<String> parseTips(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : JSON.parseArray(json, String.class);
        } catch (RuntimeException ex) {
            log.warn("Invalid AI history tips JSON", ex);
            return List.of();
        }
    }

    private List<AiDetection> parseDetections(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            Object payload = JSON.parse(json);
            if (payload instanceof JSONArray array) return array.toJavaList(AiDetection.class);
            if (payload instanceof JSONObject envelope && envelope.getJSONArray("detections") != null)
                return envelope.getJSONArray("detections").toJavaList(AiDetection.class);
            return List.of();
        } catch (RuntimeException ex) {
            log.warn("Invalid AI history detections JSON", ex);
            return new ArrayList<>();
        }
    }

    public void attachChannelContext(AiAnalysisResult result, int channelId) {
        applyChannelContext(result, requireChannel(channelId), null);
    }

    private void applyChannelContext(AiAnalysisResult result, AiChannelContext channel, String siteNameOverride) {
        result.setChannelId(Integer.toString(channel.getChannelId()));
        result.setChannelDeviceId(required(channel.getChannelDeviceId(), 128, "通道编码"));
        result.setChannelName(required(channel.getChannelName(), 255, "通道名称"));
        result.setSiteName(required(orElse(siteNameOverride, channel.getSiteName()), 255, "工地名称"));
    }

    private AiChannelContext requireChannel(int channelId) {
        AiChannelContext channel = mapper.findChannelById(channelId);
        if (channel == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "摄像头通道不存在");
        }
        return channel;
    }

    private int parseChannelId(String value) {
        try {
            int channelId = Integer.parseInt(value == null ? "" : value.trim());
            if (channelId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return channelId;
        } catch (NumberFormatException ex) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "摄像头通道 ID 无效");
        }
    }

    private ImagePayload decodeDataUrl(String dataUrl) {
        if (dataUrl == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "缺少待保存视频帧");
        }
        int separator = dataUrl.indexOf(',');
        if (separator < 0) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "视频帧格式无效");
        }
        String prefix = dataUrl.substring(0, separator).toLowerCase();
        String contentType = prefix.contains("image/png") ? "image/png" :
                (prefix.contains("image/jpeg") ? "image/jpeg" : null);
        if (contentType == null || !prefix.endsWith(";base64")) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "仅支持 JPEG 或 PNG 视频帧");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(separator + 1));
            if (bytes.length == 0 || bytes.length > config.getMaxImageBytes()) {
                throw new ControllerException(ErrorCode.ERROR400.getCode(), "视频帧大小无效");
            }
            return new ImagePayload(bytes, contentType);
        } catch (IllegalArgumentException ex) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "视频帧 Base64 数据无效");
        }
    }

    private static LocalDate recordDate(String analyzedAt) {
        try {
            return OffsetDateTime.parse(analyzedAt).toLocalDate();
        } catch (DateTimeParseException | NullPointerException ex) {
            return LocalDate.now(CHINA_ZONE);
        }
    }

    private static double clampConfidence(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return Math.round(Math.max(0D, Math.min(1D, value)) * 10_000D) / 10_000D;
    }

    private static String validateAnalysisId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "识别记录 ID 无效");
        }
        return id;
    }

    private static String required(String value, int maxLength, String field) {
        String normalized = optional(value, maxLength);
        if (normalized.isBlank()) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), field + "不能为空");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String orElse(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String imageUrl(String analysisId) {
        return "/api/ai/history/" + analysisId + "/image";
    }

    private record ImagePayload(byte[] bytes, String contentType) {
    }

    public record StoredHistoryImage(Path path, String contentType) {
    }
}
