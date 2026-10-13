package com.genersoft.iot.vmp.ai.conf;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestSizeFilterTest {
    @Test void rejectsActualOversizedBodyWithUnknownContentLength() throws Exception {
        AiAnalysisConfig config = new AiAnalysisConfig(); config.setMaxImageBytes(128 * 1024);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/analyze-frame") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[400 * 1024]); MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        new AiRequestSizeFilter(config).doFilter(request, response, (req, res) -> continued.set(true));
        assertEquals(413, response.getStatus()); assertFalse(continued.get());
    }
    @Test void preservesUtf8BodyForController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/analyze-frame");
        request.setContent("{\"channelName\":\"摄像头\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new AiRequestSizeFilter(new AiAnalysisConfig()).doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertEquals("{\"channelName\":\"摄像头\"}", req.getReader().readLine()));
    }
}
