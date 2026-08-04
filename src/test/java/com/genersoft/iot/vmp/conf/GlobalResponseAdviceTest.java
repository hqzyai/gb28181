package com.genersoft.iot.vmp.conf;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalResponseAdviceTest {

    private final GlobalResponseAdvice advice = new GlobalResponseAdvice();
    private final MethodParameter returnType = mock(MethodParameter.class);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);

    @Test
    void leavesResourceResponsesUnwrappedForTheSelectedBinaryConverter() {
        Resource image = new ByteArrayResource(new byte[]{1, 2, 3});

        Object result = advice.beforeBodyWrite(
                image,
                returnType,
                MediaType.IMAGE_JPEG,
                ResourceHttpMessageConverter.class,
                request("/api/ai/history/demo/image"),
                response);

        assertSame(image, result);
    }

    @Test
    void leavesByteArrayResponsesUnwrappedForTheSelectedBinaryConverter() {
        byte[] image = {1, 2, 3};

        Object result = advice.beforeBodyWrite(
                image,
                returnType,
                MediaType.IMAGE_JPEG,
                ByteArrayHttpMessageConverter.class,
                request("/api/image"),
                response);

        assertSame(image, result);
    }

    private static ServerHttpRequest request(String path) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost" + path));
        return request;
    }
}
