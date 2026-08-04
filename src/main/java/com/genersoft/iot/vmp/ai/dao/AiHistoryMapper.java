package com.genersoft.iot.vmp.ai.dao;

import com.genersoft.iot.vmp.ai.bean.AiChannelContext;
import com.genersoft.iot.vmp.ai.bean.AiHistoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface AiHistoryMapper {

    String HISTORY_COLUMNS = "analysis_id AS analysisId, channel_id AS channelId, " +
            "channel_device_id AS channelDeviceId, channel_name AS channelName, site_name AS siteName, " +
            "captured_at AS capturedAt, analyzed_at AS analyzedAt, video_time_seconds AS videoTimeSeconds, " +
            "model, image_width AS imageWidth, image_height AS imageHeight, image_path AS imagePath, " +
            "image_content_type AS imageContentType, elapsed_ms AS elapsedMs, status, risk_name AS riskName, " +
            "confidence, summary, tips_json AS tipsJson, detections_json AS detectionsJson, " +
            "input_tokens AS inputTokens, output_tokens AS outputTokens, total_tokens AS totalTokens, " +
            "record_source AS recordSource, created_by AS createdBy, create_time AS createTime";

    @Insert("INSERT INTO wvp_ai_analysis_history (" +
            "analysis_id, channel_id, channel_device_id, channel_name, site_name, captured_at, analyzed_at, " +
            "video_time_seconds, model, image_width, image_height, image_path, image_content_type, elapsed_ms, " +
            "status, risk_name, confidence, summary, tips_json, detections_json, input_tokens, output_tokens, " +
            "total_tokens, record_source, created_by, create_time" +
            ") VALUES (" +
            "#{analysisId}, #{channelId}, #{channelDeviceId}, #{channelName}, #{siteName}, #{capturedAt}, #{analyzedAt}, " +
            "#{videoTimeSeconds}, #{model}, #{imageWidth}, #{imageHeight}, #{imagePath}, #{imageContentType}, #{elapsedMs}, " +
            "#{status}, #{riskName}, #{confidence}, #{summary}, #{tipsJson}, #{detectionsJson}, #{inputTokens}, " +
            "#{outputTokens}, #{totalTokens}, #{recordSource}, #{createdBy}, #{createTime}" +
            ")")
    int insert(AiHistoryEntity entity);

    @Select("SELECT " + HISTORY_COLUMNS + " FROM wvp_ai_analysis_history " +
            "WHERE channel_id = #{channelId} ORDER BY analyzed_at DESC, analysis_id DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<AiHistoryEntity> findPage(@Param("channelId") int channelId,
                                   @Param("limit") int limit,
                                   @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM wvp_ai_analysis_history WHERE channel_id = #{channelId}")
    long countByChannelId(@Param("channelId") int channelId);

    @Select("SELECT " + HISTORY_COLUMNS + " FROM wvp_ai_analysis_history WHERE analysis_id = #{analysisId}")
    AiHistoryEntity findByAnalysisId(@Param("analysisId") String analysisId);

    @Select("SELECT COUNT(*) FROM wvp_ai_analysis_history WHERE analysis_id = #{analysisId}")
    int countByAnalysisId(@Param("analysisId") String analysisId);

    @Select("SELECT child.id AS channelId, " +
            "COALESCE(child.gb_device_id, child.device_id) AS channelDeviceId, " +
            "COALESCE(child.gb_name, child.name) AS channelName, " +
            "COALESCE(parent.gb_name, parent.name, child.gb_name, child.name) AS siteName " +
            "FROM wvp_device_channel child " +
            "LEFT JOIN wvp_device_channel parent ON " +
            "COALESCE(child.gb_parent_id, child.parent_id) = COALESCE(parent.gb_device_id, parent.device_id) " +
            "WHERE child.id = #{channelId} LIMIT 1")
    AiChannelContext findChannelById(@Param("channelId") int channelId);

    @Select("SELECT child.id AS channelId, " +
            "COALESCE(child.gb_device_id, child.device_id) AS channelDeviceId, " +
            "COALESCE(child.gb_name, child.name) AS channelName, " +
            "COALESCE(parent.gb_name, parent.name, child.gb_name, child.name) AS siteName " +
            "FROM wvp_device_channel child " +
            "LEFT JOIN wvp_device_channel parent ON " +
            "COALESCE(child.gb_parent_id, child.parent_id) = COALESCE(parent.gb_device_id, parent.device_id) " +
            "WHERE COALESCE(child.gb_name, child.name) = #{channelName} ORDER BY child.id LIMIT 1")
    AiChannelContext findChannelByExactName(@Param("channelName") String channelName);

    @Select("SELECT child.id AS channelId, " +
            "COALESCE(child.gb_device_id, child.device_id) AS channelDeviceId, " +
            "COALESCE(child.gb_name, child.name) AS channelName, " +
            "COALESCE(parent.gb_name, parent.name, child.gb_name, child.name) AS siteName " +
            "FROM wvp_device_channel child " +
            "LEFT JOIN wvp_device_channel parent ON " +
            "COALESCE(child.gb_parent_id, child.parent_id) = COALESCE(parent.gb_device_id, parent.device_id) " +
            "WHERE child.channel_type = 0 " +
            "AND COALESCE(child.gb_status, child.status) IS NOT NULL " +
            "AND COALESCE(child.gb_name, child.name) LIKE CONCAT('%', #{channelNamePart}, '%') " +
            "ORDER BY child.id")
    List<AiChannelContext> findChannelsByNameContaining(@Param("channelNamePart") String channelNamePart);
}
