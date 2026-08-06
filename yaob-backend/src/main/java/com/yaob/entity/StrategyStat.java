package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("strategy_stats")
public class StrategyStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    private String strategy;
    @TableField("total_trades")
    private Integer totalTrades;
    @TableField("win_trades")
    private Integer winTrades;
    @TableField("total_pnl")
    private BigDecimal totalPnl;
    @TableField("tp_count")
    private Integer tpCount;
    @TableField("sl_count")
    private Integer slCount;
    @TableField("manual_count")
    private Integer manualCount;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
