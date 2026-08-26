package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("open_positions")
public class OpenPosition {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    private String symbol;
    private String strategy;
    private String direction;
    private BigDecimal qty;
    @TableField("entry_price")
    private BigDecimal entryPrice;
    private Integer leverage;
    @TableField("tp_ratio")
    private BigDecimal tpRatio;
    @TableField("sl_ratio")
    private BigDecimal slRatio;
    private String status;
    @TableField("risk_state")
    private String riskState; // NONE/DEFENSE/RISK 持仓风控状态
    @TableField("opened_at")
    private LocalDateTime openedAt;
    @TableField("closed_at")
    private LocalDateTime closedAt;
    @TableField("close_price")
    private BigDecimal closePrice;
    @TableField("close_reason")
    private String closeReason;
    private BigDecimal pnl;
    @TableField("pnl_ratio")
    private BigDecimal pnlRatio;

    // [v3.6] G策略参考位（动态计算，替代固定百分比止盈止损）
    @TableField("defense_price")
    private BigDecimal defensePrice;      // 参考防守位(止损)
    @TableField("target_price")
    private BigDecimal targetPrice;       // 参考目标位(止盈)
    @TableField("protect_price")
    private BigDecimal protectPrice;      // 保护位(移动止损)
    @TableField("reduce_price")
    private BigDecimal reducePrice;       // 回踩减仓位
}
