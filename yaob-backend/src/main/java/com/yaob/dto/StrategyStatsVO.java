package com.yaob.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StrategyStatsVO {
    private String strategy;
    private String type;
    private Integer trades;
    private Double winRate;
    private BigDecimal pnlSum;
    private Integer tp;
    private Integer sl;
    private Integer manual;
}
