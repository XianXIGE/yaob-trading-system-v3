package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_history")
public class TradeHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("position_id")
    private Long positionId;
    private String symbol;
    private String strategy;
    private String direction;
    private BigDecimal qty;
    @TableField("entry_price")
    private BigDecimal entryPrice;
    @TableField("exit_price")
    private BigDecimal exitPrice;
    private Integer leverage;
    private BigDecimal pnl;
    @TableField("pnl_ratio")
    private BigDecimal pnlRatio;
    @TableField("close_reason")
    private String closeReason;
    @TableField("opened_at")
    private LocalDateTime openedAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
}
