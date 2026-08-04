package com.genersoft.iot.vmp.ai.conf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 JSON 反序列化前拒绝明显过大的 AI 抽帧请求，避免大体积 Base64 占满堆内存。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiRequestSizeFilter extends OncePerRequestFilter {

    private final AiAnalysisConfig config;

    public AiRequestSizeFilter(AiAnalysisConfig config) {
        this.config = config;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/ai/analyze-frame".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long maxRequestBytes = Math.max(256 * 1024L, config.getMaxImageBytes() * 4L / 3L + 128 * 1024L);
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxRequestBytes) {
            byte[] body = "{\"code\":400,\"msg\":\"AI 视频帧请求过大\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
