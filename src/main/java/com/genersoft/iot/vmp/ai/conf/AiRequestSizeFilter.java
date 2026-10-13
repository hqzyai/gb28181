package com.genersoft.iot.vmp.ai.conf;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Enforce actual body size too: chunked requests must not bypass the Base64 memory bound. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiRequestSizeFilter extends OncePerRequestFilter {
    private final AiAnalysisConfig config;
    public AiRequestSizeFilter(AiAnalysisConfig config) { this.config = config; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !"/api/ai/analyze-frame".equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        int limit = (int) Math.min(15 * 1024 * 1024L, Math.max(256 * 1024L, config.getMaxImageBytes() * 4L / 3L + 128 * 1024L));
        if (request.getContentLengthLong() > limit) { reject(response); return; }
        byte[] content = request.getInputStream().readNBytes(limit + 1);
        if (content.length > limit) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return content.length; }
            @Override public long getContentLengthLong() { return content.length; }
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream stream = new ByteArrayInputStream(content);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("AI JSON bodies use blocking reads"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
    private static void reject(HttpServletResponse response) throws IOException {
        byte[] body = "{\"code\":400,\"msg\":\"AI 视频帧请求过大\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length); response.getOutputStream().write(body);
    }
}
