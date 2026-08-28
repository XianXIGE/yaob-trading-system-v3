package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测结果归档实体。对应表: backtest_results
 * 回测/实盘双通道: 回测结论持久化，与实盘 trade_history 对照（P0-1）。
 */
@Data
@TableName("backtest_results")
public class BacktestResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String strategy;        // A-H

    @TableField("params_json")
    private String paramsJson;      // 策略参数快照

    private String symbol;          // 标的交易对
    private String interval;        // K线周期

    @TableField("start_ts")
    private Long startTs;           // 回测起始时间戳
    @TableField("end_ts")
    private Long endTs;             // 回测结束时间戳

    @TableField("total_signals")
    private Integer totalSignals;   // 产生信号数

    @TableField("total_trades")
    private Integer totalTrades;    // 成交笔数
    @TableField("win_trades")
    private Integer winTrades;
    @TableField("lose_trades")
    private Integer loseTrades;

    @TableField("win_rate")
    private BigDecimal winRate;     // 胜率(0-1)

    @TableField("total_pnl")
    private BigDecimal totalPnl;    // 累计盈亏(U)

    @TableField("max_drawdown")
    private BigDecimal maxDrawdown; // 最大回撤(%)

    @TableField("avg_win")
    private BigDecimal avgWin;
    @TableField("avg_lose")
    private BigDecimal avgLose;
    private BigDecimal sharpe;

    @TableField("slippage_pct")
    private BigDecimal slippagePct; // 假设滑点(%)

    private String status;          // RUNNING/COMPLETED/FAILED

    @TableField("created_at")
    private LocalDateTime createdAt;
}
