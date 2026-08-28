package com.yaob.dto;

import lombok.Data;

/**
 * 回测触发入参（P0-1 回测/实盘双通道）。
 */
@Data
public class BacktestRequest {
    /** 策略 key（A-H），必填 */
    private String strategy;
    /** 标的交易对（币安全原始格式如 BTCUSDT），必填 */
    private String symbol;
    /** K线周期（需 market_data 已落库），默认 1h */
    private String interval = "1h";
    /** 回测起始时间戳(epoch ms) */
    private long startTs;
    /** 回测结束时间戳(epoch ms) */
    private long endTs;
    /** 假设往返滑点(%)，默认 0.05 */
    private double slippagePct = 0.05;
}
