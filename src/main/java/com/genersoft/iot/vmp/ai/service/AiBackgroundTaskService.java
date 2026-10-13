package com.genersoft.iot.vmp.ai.service;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.ai.bean.*;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelPlayService;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/** One durable camera worker with fixed delay: never queues stale frames, never calls a paid vision API. */
@Slf4j
@Service
public class AiBackgroundTaskService {
    private final AiAnalysisConfig config;
    private final AiAnalysisService analysis;
    private final AiHistoryService history;
    private final IGbChannelService channels;
    private final IGbChannelPlayService playback;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yolo-camera-worker"); t.setDaemon(true); return t;
    });
    private final Path definition;
    private volatile Task active;
    private volatile boolean closing;

    public AiBackgroundTaskService(AiAnalysisConfig config, AiAnalysisService analysis, AiHistoryService history,
                                   IGbChannelService channels, IGbChannelPlayService playback) {
        this.config = config; this.analysis = analysis; this.history = history;
        this.channels = channels; this.playback = playback;
        this.definition = Path.of(config.getHistoryDirectory()).toAbsolutePath().normalize().resolve("yolo-task.json");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        if (!Files.exists(definition)) return;
        try { start(JSON.parseObject(Files.readString(definition), AiTaskRequest.class), "restored-task"); }
        catch (Exception ex) { log.error("YOLO background task restore failed: {}", ex.getClass().getSimpleName()); }
    }

    public synchronized Map<String, Object> start(AiTaskRequest request, String actor) {
        if (!config.isYoloConfigured()) throw error("YOLO 服务尚未配置");
        if (request == null || request.getChannelId() == null || request.getChannelId() < 1) throw error("通道 ID 无效");
        CommonGBChannel channel = channels.getOne(request.getChannelId());
        if (channel == null) throw error("摄像头通道不存在");
        int interval = request.getIntervalSeconds() == null ? config.getTaskIntervalSeconds() : request.getIntervalSeconds();
        if (interval < 1 || interval > 60) throw error("后台检测间隔须为 1 至 60 秒");
        if (active != null && active.running) {
            if (active.channelId == request.getChannelId()) return status(request.getChannelId());
            throw error("测试服务器当前仅允许一个摄像头后台任务，请先停止已有任务");
        }
        Task task = new Task(request.getChannelId(), interval, actor);
        AiTaskRequest saved = new AiTaskRequest(); saved.setChannelId(task.channelId); saved.setIntervalSeconds(interval);
        saveDefinition(saved);
        active = task;
        task.future = worker.scheduleWithFixedDelay(() -> tick(task), 0, interval, TimeUnit.SECONDS);
        return status(task.channelId);
    }

    public synchronized Map<String, Object> stop(int channelId) {
        if (active == null || active.channelId != channelId) return Map.of("running", false, "channelId", channelId);
        active.running = false;
        if (active.future != null) active.future.cancel(false);
        try { Files.deleteIfExists(definition); } catch (Exception ex) { throw error("任务已停止，但持久化状态删除失败，请联系运维"); }
        // Do not stop shared SIP playback: other viewers may still be using the stream.
        return status(channelId);
    }

    public Map<String, Object> status(int channelId) {
        Task task = active;
        if (task == null || task.channelId != channelId) return Map.of("running", false, "channelId", channelId);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("channelId", channelId); state.put("running", task.running); state.put("engine", "yolo");
        state.put("intervalSeconds", task.interval); state.put("framesAnalyzed", task.frames);
        state.put("consecutiveFailures", task.failures); state.put("error", task.error);
        state.put("lastCapturedAt", task.lastCapturedAt); state.put("state", !task.running ? "stopped" : task.error.isEmpty() ? "running" : "retrying");
        state.put("retryAfterSeconds", Math.max(0, TimeUnit.NANOSECONDS.toSeconds(task.retryAt - System.nanoTime()) + 1));
        return state;
    }

    public Map<String, Object> latest(int channelId) {
        Task task = active;
        Latest latest = task == null || task.channelId != channelId ? null : task.latest;
        if (task != null && task.channelId == channelId && !task.error.isEmpty())
            return Map.of("ready", false, "error", task.error, "lastCapturedAt", task.lastCapturedAt);
        if (latest == null) return Map.of("ready", false);
        return Map.of("ready", true, "imageData", latest.imageData(), "result", latest.result());
    }

    private void tick(Task task) {
        if (!task.running || closing || active != task || System.nanoTime() < task.retryAt) return;
        try {
            CommonGBChannel channel = channels.getOne(task.channelId);
            if (channel == null) throw error("摄像头通道不存在");
            CompletableFuture<byte[]> snapshot = new CompletableFuture<>();
            playback.getSnap(channel, (code, msg, data) -> {
                if (data == null || data.length == 0) snapshot.completeExceptionally(error("摄像头抓拍失败"));
                else snapshot.complete(data);
            });
            byte[] bytes = snapshot.get(25, TimeUnit.SECONDS);
            if (!task.running || closing) return;
            if (bytes.length > config.getMaxImageBytes()) throw error("摄像头抓拍图像过大");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw error("摄像头抓拍图像无法解码");
            AiFrameRequest frame = new AiFrameRequest(); frame.setEngine("yolo");
            frame.setChannelId(Integer.toString(task.channelId)); frame.setChannelName(channel.getGbName());
            frame.setCapturedAt(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
            frame.setImageWidth(image.getWidth()); frame.setImageHeight(image.getHeight());
            frame.setImageData("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes));
            AiAnalysisResult result = analysis.analyze(frame, "background-yolo-" + task.channelId);
            if (!task.running || closing) return;
            history.attachChannelContext(result, task.channelId);
            result.setSource("BACKGROUND");
            long now = System.nanoTime();
            boolean risk = result.getDetections().stream().anyMatch(d -> "risk".equals(d.getKind()));
            long historyInterval = TimeUnit.SECONDS.toNanos(Math.max(10, config.getTaskHistoryIntervalSeconds()));
            long riskCooldown = TimeUnit.SECONDS.toNanos(30);
            if (task.lastStored == 0 || now - task.lastStored >= historyInterval || (risk && now - task.lastRiskStored >= riskCooldown)) {
                history.persistLive(frame, result, task.actor);
                task.lastStored = now;
                if (risk) task.lastRiskStored = now;
            }
            task.latest = new Latest(frame.getImageData(), result);
            task.lastCapturedAt = frame.getCapturedAt(); task.frames++; task.failures = 0; task.error = ""; task.retryAt = 0;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // Stopped or replaced workers may finish after a new task is installed. They must not
            // mutate durable state or report failure for the active task; shutdown preserves its definition.
            if (!task.running || closing || active != task) return;
            task.failures++;
            String detail = ex instanceof ControllerException ? ((ControllerException) ex).getMsg()
                    : "摄像头抓拍或 YOLO 推理失败，本帧未完成识别";
            if (task.failures >= 5) {
                int backoffSeconds = Math.min(60, 30 + (task.failures - 5) * 10);
                task.retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(backoffSeconds);
                task.error = detail + "；连续失败 " + task.failures + " 次，" + backoffSeconds + " 秒后自动重试";
            } else {
                task.error = detail + "；稍后自动重试";
            }
            log.warn("YOLO camera task failed, channel={}, failures={}, type={}", task.channelId, task.failures, ex.getClass().getSimpleName());
        }
    }

    private void saveDefinition(AiTaskRequest request) {
        try {
            Files.createDirectories(definition.getParent());
            Path temp = definition.resolveSibling("yolo-task.json.tmp");
            Files.writeString(temp, JSON.toJSONString(request));
            Files.move(temp, definition, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) { throw error("后台任务持久化失败，任务未启动"); }
    }
    private static ControllerException error(String message) { return new ControllerException(ErrorCode.ERROR400.getCode(), message); }
    @PreDestroy public void shutdown() { closing = true; worker.shutdownNow(); }
    private record Latest(String imageData, AiAnalysisResult result) { }
    private static final class Task {
        final int channelId, interval; final String actor;
        volatile boolean running = true; volatile int failures; volatile long frames;
        volatile String error = "", lastCapturedAt = ""; volatile Latest latest; volatile ScheduledFuture<?> future;
        volatile long retryAt;
        long lastStored, lastRiskStored;
        Task(int channelId, int interval, String actor) { this.channelId = channelId; this.interval = interval; this.actor = actor; }
    }
}
