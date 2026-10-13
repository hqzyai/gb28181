package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaInfo;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OriginType;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayServiceExistingStreamTest {
    private static final String DEVICE = "34020000002000000002";
    private static final String CHANNEL = "34020000001320000001";
    private static final String STREAM = DEVICE + "_" + CHANNEL;
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};

    private static final class Fixture {
        final PlayServiceImpl service = new PlayServiceImpl();
        final IMediaServerService mediaService = mock(IMediaServerService.class);
        final IInviteStreamService invites = mock(IInviteStreamService.class);
        final IDeviceService devices = mock(IDeviceService.class);
        final IDeviceChannelService channels = mock(IDeviceChannelService.class);
        final ISIPCommander commander = mock(ISIPCommander.class);
        final IReceiveRtpServerService receivers = mock(IReceiveRtpServerService.class);
        final UserSetting settings = mock(UserSetting.class);
        final Device device = new Device(); final DeviceChannel channel = new DeviceChannel();
        final CommonGBChannel common = new CommonGBChannel(); final MediaServer node = new MediaServer();
        final MediaInfo media = new MediaInfo(); final StreamInfo stream = new StreamInfo();
        Fixture() {
            ReflectionTestUtils.setField(service, "mediaServerService", mediaService);
            ReflectionTestUtils.setField(service, "inviteStreamService", invites);
            ReflectionTestUtils.setField(service, "deviceService", devices);
            ReflectionTestUtils.setField(service, "deviceChannelService", channels);
            ReflectionTestUtils.setField(service, "cmder", commander);
            ReflectionTestUtils.setField(service, "receiveRtpServerService", receivers);
            ReflectionTestUtils.setField(service, "userSetting", settings);
            when(settings.getServerId()).thenReturn("server-1");
            device.setId(1); device.setDeviceId(DEVICE); device.setServerId("server-1"); device.setMediaServerId("auto");
            channel.setId(1); channel.setGbId(1); channel.setDeviceId(CHANNEL);
            common.setGbId(1); common.setDataDeviceId(1);
            node.setId("media-1"); node.setStatus(true);
            media.setApp(MediaStreamUtil.RTP_APP); media.setStream(STREAM); media.setSchema("rtsp");
            media.setOriginType(OriginType.RTP_PUSH.ordinal()); media.setOnline(true); media.setMediaServer(node);
            stream.setApp(MediaStreamUtil.RTP_APP); stream.setStream(STREAM); stream.setMediaServer(node); stream.setMediaInfo(media);
            when(devices.getDevice(1)).thenReturn(device);
            when(channels.getOneForSourceById(1)).thenReturn(channel);
            when(mediaService.getAllOnlineList()).thenReturn(List.of(node));
            when(mediaService.getOne("media-1")).thenReturn(node);
            when(mediaService.getMediaServerForMinimumLoad(null)).thenReturn(node);
            when(mediaService.getMediaInfo(node, MediaStreamUtil.RTP_APP, STREAM)).thenReturn(media);
            when(mediaService.getStreamInfoByAppAndStream(eq(node), eq(MediaStreamUtil.RTP_APP), eq(STREAM), eq(media), isNull())).thenReturn(stream);
            when(mediaService.getSnap(node, MediaStreamUtil.RTP_APP, STREAM, 15, 1, null, null)).thenReturn(JPEG);
        }
        void noNewOrClosedSipStream() {
            verifyNoInteractions(commander, receivers);
            verify(invites, never()).updateInviteInfo(any());
            verify(mediaService, never()).closeStreams(any(), anyString(), anyString());
            verify(mediaService, never()).closeRTPServer(any(), anyString(), anyString());
        }
    }

    @Test void restartingWvpWithExistingViewerReusesActualRtpForBothPlayEntrypoints() {
        Fixture fixture = new Fixture();
        AtomicReference<StreamInfo> actual = new AtomicReference<>();
        fixture.service.play(fixture.device, fixture.channel, (code, msg, data) -> { assertEquals(0, code); actual.set(data); });
        assertSame(fixture.stream, actual.get());
        fixture.service.play(fixture.common, false, (code, msg, data) -> { assertEquals(0, code); actual.set(data); });
        assertEquals(DEVICE, actual.get().getDeviceId()); assertEquals(Integer.valueOf(1), actual.get().getChannelId());
        verify(fixture.channels, times(1)).startPlay(1, STREAM);
        // The second lookup uses one cached known node; it does not scan all media nodes or all streams.
        verify(fixture.mediaService, times(1)).getAllOnlineList();
        verify(fixture.mediaService, never()).getMediaList(any(), anyString(), anyString(), any());
        fixture.noNewOrClosedSipStream();
    }

    @Test void snapshotReusesSameCameraLiveMediaWhenInviteCacheIsMissing() {
        Fixture fixture = new Fixture();
        AtomicReference<byte[]> actual = new AtomicReference<>();
        fixture.service.getSnap(fixture.common, (code, msg, data) -> { assertEquals(0, code); actual.set(data); });
        assertArrayEquals(JPEG, actual.get());
        verify(fixture.mediaService).getSnap(fixture.node, MediaStreamUtil.RTP_APP, STREAM, 15, 1, null, null);
        fixture.noNewOrClosedSipStream();
    }

    @Test void sourceAppearingDuringSnapshotPlaybackRaceDoesNotRequireFabricatedInvite() {
        Fixture fixture = new Fixture();
        when(fixture.mediaService.getMediaInfo(fixture.node, MediaStreamUtil.RTP_APP, STREAM))
                .thenReturn(null, fixture.media);
        AtomicReference<byte[]> actual = new AtomicReference<>();
        fixture.service.getSnap(fixture.common, (code, msg, data) -> { assertEquals(0, code); actual.set(data); });
        assertArrayEquals(JPEG, actual.get());
        verify(fixture.mediaService, times(2)).getMediaInfo(fixture.node, MediaStreamUtil.RTP_APP, STREAM);
        fixture.noNewOrClosedSipStream();
    }

    @Test void doesNotReuseOtherCameraAppOrNonRtpPublisher() {
        Fixture fixture = new Fixture();
        fixture.media.setStream("other-camera"); assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.media.setStream(STREAM); fixture.media.setApp("proxy"); assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.media.setApp(MediaStreamUtil.RTP_APP); fixture.media.setOriginType(OriginType.RTSP_PUSH.ordinal());
        assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.media.setOriginType(OriginType.RTP_PUSH.ordinal()); fixture.media.setOnline(false);
        assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.noNewOrClosedSipStream();
    }

    @Test void unassignedOrOfflineMediaAndForeignWvpDeviceAreNotReused() {
        Fixture fixture = new Fixture();
        fixture.device.setMediaServerId("other-media"); assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        verify(fixture.mediaService, never()).getMediaInfo(any(), anyString(), anyString());
        fixture.device.setMediaServerId("auto"); fixture.node.setStatus(false);
        assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.node.setStatus(true); fixture.device.setServerId("other-wvp");
        assertNull(fixture.service.findExistingPlayStream(fixture.device, fixture.channel));
        fixture.noNewOrClosedSipStream();
    }

    @Test void normalWarmInviteSnapshotKeepsExistingPathWithoutMediaNodeScan() {
        Fixture fixture = new Fixture();
        InviteInfo warm = new InviteInfo(); warm.setStreamInfo(fixture.stream); warm.setSsrcInfo(new SSRCInfo(30000, "1", MediaStreamUtil.RTP_APP, STREAM));
        when(fixture.invites.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1)).thenReturn(warm);
        when(fixture.mediaService.getSnap(fixture.node, MediaStreamUtil.RTP_APP, STREAM, 15, 1, "snap", null)).thenReturn(JPEG);
        fixture.service.getSnap(fixture.common, (code, msg, data) -> { assertEquals(0, code); assertArrayEquals(JPEG, data); });
        verify(fixture.mediaService, never()).getAllOnlineList();
        fixture.noNewOrClosedSipStream();
    }
}
