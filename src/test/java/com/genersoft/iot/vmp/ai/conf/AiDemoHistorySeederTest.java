package com.genersoft.iot.vmp.ai.conf;

import com.genersoft.iot.vmp.ai.bean.AiAnalysisResult;
import com.genersoft.iot.vmp.ai.service.AiHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDemoHistorySeederTest {

    @Test
    void seedsEachCameraWithItsOwnIdempotentDemoRecords() {
        AiAnalysisConfig config = demoConfig();
        AiHistoryService historyService = mock(AiHistoryService.class);
        when(historyService.findChannelIdByExactName(config.getDemoChannelName())).thenReturn(35);
        when(historyService.findChannelIdByExactName(config.getDemoUniversityChannelName())).thenReturn(59);
        when(historyService.seedDemo(
                anyInt(), anyString(), any(AiAnalysisResult.class), any(byte[].class), eq("image/jpeg")))
                .thenReturn(true);

        new AiDemoHistorySeeder(config, historyService).run(mock(ApplicationArguments.class));

        ArgumentCaptor<Integer> channelCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> siteCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiAnalysisResult> resultCaptor = ArgumentCaptor.forClass(AiAnalysisResult.class);
        ArgumentCaptor<byte[]> imageCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(historyService, times(12)).seedDemo(
                channelCaptor.capture(), siteCaptor.capture(), resultCaptor.capture(),
                imageCaptor.capture(), eq("image/jpeg"));

        assertEquals(8, channelCaptor.getAllValues().stream().filter(id -> id == 35).count());
        assertEquals(4, channelCaptor.getAllValues().stream().filter(id -> id == 59).count());
        assertEquals(8, siteCaptor.getAllValues().stream().filter(config.getDemoSiteName()::equals).count());
        assertEquals(4, siteCaptor.getAllValues().stream().filter(config.getDemoUniversitySiteName()::equals).count());
        assertTrue(imageCaptor.getAllValues().stream().allMatch(image -> image.length > 0));

        Map<String, AiAnalysisResult> universityResults = resultCaptor.getAllValues().stream()
                .filter(result -> "吊运散料未使用料斗".equals(result.getRiskName()))
                .collect(Collectors.toMap(AiAnalysisResult::getAnalysisId, Function.identity()));
        assertEquals(4, universityResults.size());
        assertEquals(0.65D, universityResults.get("demo-university-p2s4-20260711160734-65").getConfidence());
        assertEquals(0.70D, universityResults.get("demo-university-p2s4-20260711160739-70").getConfidence());
        assertEquals(0.68D, universityResults.get("demo-university-p2s4-20260711160739-68").getConfidence());
        assertEquals(0.72D, universityResults.get("demo-university-p2s4-20260711160739-72").getConfidence());
        assertTrue(universityResults.values().stream().allMatch(result -> "人工复核".equals(result.getStatus())));
        assertTrue(universityResults.values().stream().allMatch(result -> result.getImageWidth() == 1920));
        assertTrue(universityResults.values().stream().allMatch(result -> result.getImageHeight() == 1080));
        assertTrue(universityResults.values().stream()
                .allMatch(result -> result.getSummary().contains("散料坠落风险")));
    }

    @Test
    void missingOneDemoCameraDoesNotBlockOtherDatasets() {
        AiAnalysisConfig config = demoConfig();
        AiHistoryService historyService = mock(AiHistoryService.class);
        when(historyService.findChannelIdByExactName(config.getDemoChannelName())).thenReturn(null);
        when(historyService.findChannelIdByExactName(config.getDemoUniversityChannelName())).thenReturn(59);
        when(historyService.seedDemo(
                anyInt(), anyString(), any(AiAnalysisResult.class), any(byte[].class), eq("image/jpeg")))
                .thenReturn(true);

        new AiDemoHistorySeeder(config, historyService).run(mock(ApplicationArguments.class));

        verify(historyService, times(4)).seedDemo(
                eq(59), eq(config.getDemoUniversitySiteName()), any(AiAnalysisResult.class),
                any(byte[].class), eq("image/jpeg"));
    }

    private static AiAnalysisConfig demoConfig() {
        AiAnalysisConfig config = new AiAnalysisConfig();
        config.setDemoEnabled(true);
        return config;
    }
}
