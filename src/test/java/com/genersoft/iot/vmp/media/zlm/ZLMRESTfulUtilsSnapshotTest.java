package com.genersoft.iot.vmp.media.zlm;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ZLMRESTfulUtilsSnapshotTest {
    @TempDir Path temp;
    private static final byte[] JPEG = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};

    private static final class TrackedBody extends ResponseBody {
        final AtomicBoolean closed = new AtomicBoolean();
        final BufferedSource buffered;
        final long declaredLength;
        TrackedBody(byte[] bytes, long declaredLength) {
            this.declaredLength = declaredLength;
            buffered = Okio.buffer(new ForwardingSource(new Buffer().write(bytes)) {
                @Override public void close() throws IOException { closed.set(true); super.close(); }
            });
        }
        @Override public MediaType contentType() { return MediaType.get("image/jpeg"); }
        @Override public long contentLength() { return declaredLength; }
        @Override public BufferedSource source() { return buffered; }
    }

    private static MediaServer media() {
        MediaServer media = new MediaServer(); media.setIp("127.0.0.1"); media.setHttpPort(28081);
        media.setSecret("test-only-secret"); return media;
    }

    private static ZLMRESTfulUtils utils(TrackedBody body, int status) throws Exception {
        ZLMRESTfulUtils utils = new ZLMRESTfulUtils();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("/index/api/getSnap", chain.request().url().encodedPath());
            assertEquals("test-only-secret", chain.request().url().queryParameter("secret"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test response").body(body).build();
        }).build();
        Field field = ZLMRESTfulUtils.class.getDeclaredField("client"); field.setAccessible(true); field.set(utils, http);
        return utils;
    }

    @Test void coldStreamBytesOnlySnapshotReturnsJpegAndClosesResponse() throws Exception {
        TrackedBody body = new TrackedBody(JPEG, JPEG.length);
        byte[] result = utils(body, 200).getSnap(media(), "rtsp://127.0.0.1:5540/rtp/camera", 15, 1, null, null);
        assertArrayEquals(JPEG, result);
        assertTrue(body.closed.get());
    }

    @Test void existingStreamWithNullFilenameDoesNotCreateSnapNullFile() throws Exception {
        TrackedBody body = new TrackedBody(JPEG, -1);
        Path absent = temp.resolve("unneeded-subdirectory");
        byte[] result = utils(body, 200).sendGetForImg(media(), "getSnap", Map.of(), absent.toString(), null);
        assertArrayEquals(JPEG, result);
        assertFalse(Files.exists(absent));
        assertTrue(body.closed.get());
    }

    @Test void namedSnapshotKeepsLegacyDiskOutputAndAlsoReturnsBytes() throws Exception {
        TrackedBody body = new TrackedBody(JPEG, JPEG.length);
        Path directory = temp.resolve("snap");
        byte[] result = utils(body, 200).sendGetForImg(media(), "getSnap", Map.of(), directory.toString(), "camera.jpg");
        assertArrayEquals(JPEG, result);
        assertArrayEquals(JPEG, Files.readAllBytes(directory.resolve("camera.jpg")));
        assertTrue(body.closed.get());
    }

    @Test void failedMediaResponseReturnsFailureAndClosesWithoutCreatingFile() throws Exception {
        TrackedBody body = new TrackedBody(JPEG, JPEG.length);
        assertNull(utils(body, 503).sendGetForImg(media(), "getSnap", Map.of(), temp.resolve("unused").toString(), "camera.jpg"));
        assertTrue(body.closed.get());
        assertFalse(Files.exists(temp.resolve("unused")));
    }

    @Test void declaredOversizedImageIsRejectedBeforeDiskWriteAndClosed() throws Exception {
        TrackedBody body = new TrackedBody(JPEG, 3 * 1024 * 1024L + 1);
        assertNull(utils(body, 200).sendGetForImg(media(), "getSnap", Map.of(), temp.resolve("unused").toString(), "camera.jpg"));
        assertTrue(body.closed.get());
        assertFalse(Files.exists(temp.resolve("unused")));
    }

    @Test void unknownLengthOversizedImageCannotBypassStreamingLimit() throws Exception {
        TrackedBody body = new TrackedBody(new byte[3 * 1024 * 1024 + 1], -1);
        assertNull(utils(body, 200).sendGetForImg(media(), "getSnap", Map.of(), null, null));
        assertTrue(body.closed.get());
    }
}
