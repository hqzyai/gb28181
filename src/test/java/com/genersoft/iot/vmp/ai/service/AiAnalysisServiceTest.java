package com.genersoft.iot.vmp.ai.service;

import com.genersoft.iot.vmp.ai.bean.AiDetection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnalysisServiceTest {

    @Test
    void parsesFencedJsonAndNormalizesFractionalCoordinates() {
        String content = """
                ```json
                [{"type":"高处临边无安全防护设施","confidence":91,"x":0.1,"y":0.2,"width":0.5,"height":0.4}]
                ```
                """;

        List<AiDetection> detections = AiAnalysisService.parseDetections(content, 1000, 500, 0.60, 0.85);

        assertEquals(1, detections.size());
        AiDetection detection = detections.get(0);
        assertEquals("高处临边无安全防护", detection.getType());
        assertEquals(0.91, detection.getConfidence());
        assertEquals(100, detection.getX());
        assertEquals(100, detection.getY());
        assertEquals(500, detection.getWidth());
        assertEquals(200, detection.getHeight());
        assertEquals("自动告警", detection.getStatus());
    }

    @Test
    void rejectsUnknownLowConfidenceAndInvalidBoxes() {
        String content = """
                [
                  {"type":"未知风险","confidence":0.99,"x":1,"y":1,"width":20,"height":20},
                  {"type":"钢筋长短吊","confidence":0.59,"x":1,"y":1,"width":20,"height":20},
                  {"type":"钢筋长短吊","confidence":0.88,"x":1,"y":1,"width":0,"height":20}
                ]
                """;

        assertTrue(AiAnalysisService.parseDetections(content, 1280, 720, 0.60, 0.85).isEmpty());
    }

    @Test
    void rejectsVagueRiskNamesInsteadOfGuessingACategory() {
        String content = "[{\"type\":\"高处\",\"confidence\":0.99,\"x\":1,\"y\":1,\"width\":20,\"height\":20}]";

        assertTrue(AiAnalysisService.parseDetections(content, 1280, 720, 0.60, 0.85).isEmpty());
    }

    @Test
    void clampsBoxesToImageBounds() {
        String content = "[{\"type\":\"动火现场可燃物\",\"confidence\":0.72,\"x\":900,\"y\":450,\"width\":400,\"height\":200}]";

        List<AiDetection> detections = AiAnalysisService.parseDetections(content, 1000, 500, 0.60, 0.85);

        assertEquals(1, detections.size());
        assertEquals(100, detections.get(0).getWidth());
        assertEquals(50, detections.get(0).getHeight());
        assertEquals("人工复核", detections.get(0).getStatus());
    }
}
