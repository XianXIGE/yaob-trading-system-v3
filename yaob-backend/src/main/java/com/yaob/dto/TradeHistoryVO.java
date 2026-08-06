package com.yaob.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TradeHistoryVO {
    private Long id;
    private String symbol;
    private String strategy;
    private String direction;
    private BigDecimal qty;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private Integer leverage;
    private BigDecimal pnl;
    private BigDecimal pnlRatio;
    private String closeReason;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
}
