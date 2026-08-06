package com.yaob.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class DashboardVO {
    private String scannerStatus;
    private double lastScanDuration;
    private long nextScanTimestamp;
    private long scanStartTimestamp;
    private BigDecimal accountTotalAssets;
    private BigDecimal availableMargin;
    private BigDecimal openMargin;
    private Integer leverage;
    private Boolean autoTradeEnabled;
    private String marginMode;
    private Boolean excludeLargeCap;
    private Boolean hasApiKey;
    private Boolean isVip;
    private String vipExpireAt;
    private Map<String, Boolean> strategyStates;
    private List<Map<String, Object>> candidatePool;
    private List<Map<String, Object>> positions;
}
