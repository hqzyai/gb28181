package com.genersoft.iot.vmp.ai.service;

import com.genersoft.iot.vmp.ai.bean.AiChannelContext;
import com.genersoft.iot.vmp.ai.bean.AiHistoryEntity;
import com.genersoft.iot.vmp.ai.bean.AiHistoryPage;
import com.genersoft.iot.vmp.ai.conf.AiAnalysisConfig;
import com.genersoft.iot.vmp.ai.dao.AiHistoryMapper;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoryServiceTest {

    @Test
    void pagesHistoryByChannelWithFixedTenRows() {
        AiHistoryMapper mapper = mock(AiHistoryMapper.class);
        AiHistoryStorage storage = mock(AiHistoryStorage.class);
        AiHistoryService service = new AiHistoryService(mapper, storage, new AiAnalysisConfig());
        when(mapper.findChannelById(35)).thenReturn(channel());
        when(mapper.countByChannelId(35)).thenReturn(21L);
        when(mapper.findPage(35, 10, 10L)).thenReturn(List.of(historyEntity()));

        AiHistoryPage page = service.list("35", 2);

        assertEquals(21L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(10, page.getPageSize());
        assertEquals(3, page.getPages());
        assertEquals(1, page.getList().size());
        assertEquals("归林驿-东北角作业区", page.getList().get(0).getChannelName());
        assertEquals("/api/ai/history/history-test-001/image", page.getList().get(0).getImageUrl());
        assertNull(page.getList().get(0).getVideoTimeSeconds());
        verify(mapper).findPage(35, 10, 10L);
    }

    @Test
    void rejectsInvalidChannelBeforeQueryingHistory() {
        AiHistoryService service = new AiHistoryService(
                mock(AiHistoryMapper.class), mock(AiHistoryStorage.class), new AiAnalysisConfig());

        ControllerException exception = assertThrows(ControllerException.class, () -> service.list("not-a-number", 1));

        assertEquals(400, exception.getCode());
    }

    @Test
    void findsAllCameraChannelsByTrimmedNamePart() {
        AiHistoryMapper mapper = mock(AiHistoryMapper.class);
        AiHistoryService service = new AiHistoryService(
                mapper, mock(AiHistoryStorage.class), new AiAnalysisConfig());
        AiChannelContext south = channel();
        south.setChannelId(37);
        south.setChannelName("青藤小镇项目(南区)-2#塔吊-球机");
        when(mapper.findChannelsByNameContaining("青藤小镇")).thenReturn(List.of(south));

        List<AiChannelContext> channels = service.findChannelsByNameContaining("  青藤小镇  ");

        assertEquals(List.of(south), channels);
        verify(mapper).findChannelsByNameContaining("青藤小镇");
    }

    @Test
    void blankCameraNamePartDoesNotQueryDatabase() {
        AiHistoryMapper mapper = mock(AiHistoryMapper.class);
        AiHistoryService service = new AiHistoryService(
                mapper, mock(AiHistoryStorage.class), new AiAnalysisConfig());

        assertEquals(List.of(), service.findChannelsByNameContaining("  "));
    }

    private static AiChannelContext channel() {
        AiChannelContext context = new AiChannelContext();
        context.setChannelId(35);
        context.setChannelDeviceId("13310200701317138211");
        context.setChannelName("归林驿-东北角作业区");
        context.setSiteName("雄安湾生态基地");
        return context;
    }

    private static AiHistoryEntity historyEntity() {
        AiHistoryEntity entity = new AiHistoryEntity();
        entity.setAnalysisId("history-test-001");
        entity.setChannelId(35);
        entity.setChannelDeviceId("13310200701317138211");
        entity.setChannelName("归林驿-东北角作业区");
        entity.setSiteName("雄安湾生态基地");
        entity.setCapturedAt("2026-07-21T10:34:19+08:00");
        entity.setAnalyzedAt("2026-07-21T10:34:19+08:00");
        entity.setVideoTimeSeconds(58.56D);
        entity.setModel("demo-history-v1");
        entity.setImageWidth(1784);
        entity.setImageHeight(852);
        entity.setElapsedMs(0L);
        entity.setStatus("人工复核");
        entity.setRiskName("动火可燃物");
        entity.setConfidence(0.8D);
        entity.setSummary("需人工复核");
        entity.setTipsJson("[]");
        entity.setDetectionsJson("[]");
        entity.setRecordSource("DEMO");
        return entity;
    }
}
