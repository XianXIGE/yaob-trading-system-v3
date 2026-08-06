package com.yaob.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PositionVO {
    private Long id;
    private String symbol;
    private String strategy;
    private String direction;
    private BigDecimal qty;
    private BigDecimal entryPrice;
    private Integer leverage;
    private BigDecimal tpRatio;
    private BigDecimal slRatio;
    private String status;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private BigDecimal closePrice;
    private String closeReason;
    private BigDecimal pnl;
    private BigDecimal pnlRatio;
    // 实时字段
    private BigDecimal currentPrice;
    private BigDecimal margin;
}
