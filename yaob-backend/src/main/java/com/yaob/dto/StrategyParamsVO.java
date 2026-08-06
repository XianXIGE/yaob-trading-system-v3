package com.yaob.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StrategyParamsVO {
    private Map<String, Map<String, Object>> a;
    private Map<String, Map<String, Object>> b;
    private Map<String, Map<String, Object>> c;
    private Map<String, Map<String, Object>> d;
    private Map<String, Map<String, Object>> e;
    private Map<String, Map<String, Object>> f;
}
