package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * K线行情落库实体。对应表: market_data
 * 用途: 回测数据源 + ATR/波动率计算的原始数据（P0-1）。
 */
@Data
@TableName("market_data")
public class MarketData {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;          // 币安全原始交易对，如 BTCUSDT

    // interval 是 MySQL 保留字，MyBatis-Plus 自动生成 INSERT 列名时不加反引号会 SQL 语法报错，故显式用反引号包裹。
    @TableField("`interval`")
    private String interval;        // 1m/5m/15m/1h/4h/1d

    @TableField("open_time")
    private Long openTime;          // K线开盘时间(币安毫秒时间戳)

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;

    @TableField("quote_volume")
    private BigDecimal quoteVolume; // 成交额(USDT)

    @TableField("created_at")
    private LocalDateTime createdAt;
}
