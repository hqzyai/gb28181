package com.genersoft.iot.vmp.ai.conf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiHistorySchemaInitializer implements ApplicationRunner {

    static final String TABLE_NAME = "wvp_ai_analysis_history";
    static final String INDEX_NAME = "idx_wvp_ai_history_channel_time";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public AiHistorySchemaInitializer(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS wvp_ai_analysis_history (
                    analysis_id VARCHAR(64) NOT NULL PRIMARY KEY,
                    channel_id INTEGER NOT NULL,
                    channel_device_id VARCHAR(128) NOT NULL,
                    channel_name VARCHAR(255) NOT NULL,
                    site_name VARCHAR(255) NOT NULL,
                    captured_at VARCHAR(64) NOT NULL,
                    analyzed_at VARCHAR(64) NOT NULL,
                    video_time_seconds DOUBLE PRECISION,
                    model VARCHAR(128) NOT NULL,
                    image_width INTEGER NOT NULL,
                    image_height INTEGER NOT NULL,
                    image_path VARCHAR(512) NOT NULL,
                    image_content_type VARCHAR(64) NOT NULL,
                    elapsed_ms BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    risk_name VARCHAR(255) NOT NULL,
                    confidence DOUBLE PRECISION NOT NULL,
                    summary TEXT NOT NULL,
                    tips_json TEXT NOT NULL,
                    detections_json TEXT NOT NULL,
                    input_tokens INTEGER NOT NULL,
                    output_tokens INTEGER NOT NULL,
                    total_tokens INTEGER NOT NULL,
                    record_source VARCHAR(32) NOT NULL,
                    created_by VARCHAR(200) NOT NULL,
                    create_time VARCHAR(64) NOT NULL
                )
                """);
        if (!indexExists()) {
            jdbcTemplate.execute("CREATE INDEX " + INDEX_NAME + " ON " + TABLE_NAME + " (channel_id, analyzed_at)");
        }
        log.info("AI history schema is ready");
    }

    private boolean indexExists() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet indexes = metadata.getIndexInfo(
                    connection.getCatalog(), connection.getSchema(), TABLE_NAME, false, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    if (INDEX_NAME.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
