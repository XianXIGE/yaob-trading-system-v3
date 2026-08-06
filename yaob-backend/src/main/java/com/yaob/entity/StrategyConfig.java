package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("strategy_configs")
public class StrategyConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    private String strategy;
    @TableField("strategy_type")
    private String strategyType;
    private String description;
    private Boolean enabled;
    @TableField("tp_ratio")
    private BigDecimal tpRatio;
    @TableField("sl_ratio")
    private BigDecimal slRatio;
    @TableField("params_json")
    private String paramsJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
