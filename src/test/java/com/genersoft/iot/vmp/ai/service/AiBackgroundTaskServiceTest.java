package com.genersoft.iot.vmp.ai.service;

import com.genersoft.iot.vmp.ai.bean.*;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiBackgroundTaskServiceTest {
    @TempDir Path temp;
    private AiAnalysisConfig config() {
        AiAnalysisConfig config = new AiAnalysisConfig(); config.setEnabled(true); config.setYoloEnabled(true);
        config.setYoloToken("private-test-token"); config.setHistoryDirectory(temp.toString()); return config;
    }
    private static AiTaskRequest request() { AiTaskRequest request = new AiTaskRequest(); request.setChannelId(35); request.setIntervalSeconds(1); return request; }
    private static CommonGBChannel channel() { CommonGBChannel channel = new CommonGBChannel(); channel.setGbId(35); channel.setGbName("摄像头"); return channel; }
    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpeg", out); return out.toByteArray();
    }
    @Test void independentWorkerPersistsDefinitionAndLatestFrameWithoutBrowser() throws Exception {
        AiAnalysisService analysis = mock(AiAnalysisService.class); AiHistoryService history = mock(AiHistoryService.class);
        IGbChannelService channels = mock(IGbChannelService.class); IGbChannelPlayService playback = mock(IGbChannelPlayService.class);
        when(channels.getOne(35)).thenReturn(channel());
        byte[] jpeg = jpeg();
        doAnswer(invocation -> { ErrorCallback<byte[]> cb = invocation.getArgument(1); cb.run(0, "ok", jpeg); return null; }).when(playback).getSnap(any(), any());
        when(analysis.analyze(any(), anyString())).thenAnswer(invocation -> {
            AiFrameRequest frame = invocation.getArgument(0); assertEquals("yolo", frame.getEngine());
            AiAnalysisResult result = new AiAnalysisResult(); result.setAnalysisId("test-frame"); result.setDetections(List.of()); return result;
        });
        AiBackgroundTaskService tasks = new AiBackgroundTaskService(config(), analysis, history, channels, playback);
        try {
            tasks.start(request(), "user");
            await(() -> ((Number) tasks.status(35).get("framesAnalyzed")).longValue() >= 2, 5);
            assertTrue(Files.exists(temp.resolve("yolo-task.json")));
            assertEquals(true, tasks.latest(35).get("ready"));
            assertTrue(((String) tasks.latest(35).get("imageData")).startsWith("data:image/jpeg;base64,"));
            verify(history, times(1)).persistLive(any(), any(), eq("user"));
            tasks.stop(35);
            assertFalse(Files.exists(temp.resolve("yolo-task.json")));
            assertEquals(false, tasks.status(35).get("running"));
        } finally { tasks.shutdown(); }
    }
    @Test void repeatedCaptureFailureBacksOffDurablyWithoutNormalResult() throws Exception {
        IGbChannelService channels = mock(IGbChannelService.class); IGbChannelPlayService playback = mock(IGbChannelPlayService.class);
        AiAnalysisService analysis = mock(AiAnalysisService.class); when(channels.getOne(35)).thenReturn(channel());
        doAnswer(invocation -> { ErrorCallback<byte[]> cb = invocation.getArgument(1); cb.run(100, "failed", null); return null; }).when(playback).getSnap(any(), any());
        AiBackgroundTaskService tasks = new AiBackgroundTaskService(config(), analysis, mock(AiHistoryService.class), channels, playback);
        try {
            tasks.start(request(), "user");
            await(() -> Integer.valueOf(5).equals(tasks.status(35).get("consecutiveFailures")) && ((Number) tasks.status(35).get("retryAfterSeconds")).longValue() >= 25, 8);
            assertEquals(true, tasks.status(35).get("running"));
            assertEquals("retrying", tasks.status(35).get("state"));
            assertTrue(Files.exists(temp.resolve("yolo-task.json")));
            assertEquals(false, tasks.latest(35).get("ready"));
            assertTrue(((Number) tasks.status(35).get("retryAfterSeconds")).longValue() >= 25);
            assertTrue(((String) tasks.status(35).get("error")).contains("自动重试"));
            verifyNoInteractions(analysis);
        } finally { tasks.shutdown(); }
    }
    @Test void oldWorkerFailureAfterStopAndReplacementCannotChangeNewDefinition() throws Exception {
        AiAnalysisService analysis = mock(AiAnalysisService.class);
        IGbChannelService channels = mock(IGbChannelService.class); IGbChannelPlayService playback = mock(IGbChannelPlayService.class);
        when(channels.getOne(anyInt())).thenAnswer(invocation -> { CommonGBChannel c = channel(); c.setGbId(invocation.getArgument(0)); return c; });
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            CommonGBChannel c = invocation.getArgument(0);
            if (c.getGbId() == 35) { if (attempts.incrementAndGet() <= 4) throw new RuntimeException("transient failure"); entered.countDown(); release.await(5, TimeUnit.SECONDS); throw new RuntimeException("old worker failed"); }
            ErrorCallback<byte[]> cb = invocation.getArgument(1); cb.run(0, "ok", jpeg()); return null;
        }).when(playback).getSnap(any(), any());
        when(analysis.analyze(any(), anyString())).thenAnswer(invocation -> { AiAnalysisResult r = new AiAnalysisResult(); r.setDetections(List.of()); return r; });
        AiBackgroundTaskService tasks = new AiBackgroundTaskService(config(), analysis, mock(AiHistoryService.class), channels, playback);
        try {
            tasks.start(request(), "user"); assertTrue(entered.await(7, TimeUnit.SECONDS));
            tasks.stop(35);
            AiTaskRequest replacement = request(); replacement.setChannelId(36); tasks.start(replacement, "new-user");
            release.countDown();
            await(() -> ((Number) tasks.status(36).get("framesAnalyzed")).longValue() >= 1, 5);
            assertEquals(Integer.valueOf(36), com.alibaba.fastjson2.JSON.parseObject(Files.readString(temp.resolve("yolo-task.json")), AiTaskRequest.class).getChannelId());
            assertEquals(0, tasks.status(36).get("consecutiveFailures"));
            assertEquals(true, tasks.status(36).get("running"));
        } finally { release.countDown(); tasks.shutdown(); }
    }
    @Test void shutdownDuringCaptureRetainsDefinitionForRestart() throws Exception {
        IGbChannelService channels = mock(IGbChannelService.class); IGbChannelPlayService playback = mock(IGbChannelPlayService.class);
        when(channels.getOne(35)).thenReturn(channel());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 4) throw new RuntimeException("transient failure");
            entered.countDown();
            try { Thread.sleep(10000); } catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
            return null;
        }).when(playback).getSnap(any(), any());
        AiBackgroundTaskService tasks = new AiBackgroundTaskService(config(), mock(AiAnalysisService.class), mock(AiHistoryService.class), channels, playback);
        try {
            tasks.start(request(), "user"); assertTrue(entered.await(7, TimeUnit.SECONDS));
            tasks.shutdown(); assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(Files.exists(temp.resolve("yolo-task.json")));
            assertEquals(4, tasks.status(35).get("consecutiveFailures"));
        } finally { tasks.shutdown(); }
    }

    private static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "worker condition timed out after " + Duration.ofSeconds(seconds));
    }
}
