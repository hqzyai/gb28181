package com.genersoft.iot.vmp.ai.bean;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AiHistoryPage {
    private List<AiAnalysisResult> list;
    private long total;
    private int page;
    private int pageSize;
    private int pages;
}
