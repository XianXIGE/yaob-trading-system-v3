package com.yaob.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaob.entity.BacktestResult;
import com.yaob.entity.MarketData;
import com.yaob.mapper.BacktestResultMapper;
import com.yaob.mapper.MarketDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yaob.service.TradeMath.*;

/**
 * 回测通道 Runner（P0-1 回测/实盘双通道）。
 * <p>
 * 复用与实盘完全一致的 StrategyDetectorService.check()（无状态纯函数），
 * 对已落库的 market_data K线做历史回放，模拟"每根K线收盘时检测信号 → 按参数止盈止损平仓"，
 * 统计胜率/盈亏比/最大回撤，写入 backtest_results 表。
 * <p>
 * 关键设计：回测与实盘共用同一信号函数，保证"回测得过的结论"在实盘可复现；
 * 与实盘 trade_history 形成对照。样本建议 ≥3个月（分析师要求，日内/短时策略至少用分钟级）。
 */
@Slf4j
@Service
public class BacktestRunner {

    @Autowired
    private MarketDataMapper marketDataMapper;
    @Autowired
    private BacktestResultMapper backtestResultMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private StrategyDetectorService strategyDetector;
    @Autowired
    private ObjectMapper objectMapper;

    /** 单笔开仓保证金(美元)，用于把收益率换算为金额（回测用模拟口径） */
    private static final double BT_MARGIN = 5.0;

    /**
     * 对单个标的单策略执行一次回测。
     *
     * @param userId   用户ID（取其策略参数，与实盘配置对齐）
     * @param strategy 策略 key（A-H）
     * @param symbol   标的交易对（币安全原始格式，如 ANTHROPICUSDT）
     * @param interval K线周期（使用 market_data 已落库的周期）
     * @param startTs  回测起始时间戳(epoch ms)
     * @param endTs    回测结束时间戳(epoch ms)
     * @param slippagePct 假设往返滑点(%)，计入成本
     */
    public BacktestResult run(Long userId, String strategy, String symbol, String interval,
                              long startTs, long endTs, double slippagePct) {
        String sk = strategy.toUpperCase();
        BacktestResult r = new BacktestResult();
        r.setStrategy(sk);
        r.setSymbol(symbol);
        r.setInterval(interval);
        r.setStartTs(startTs);
        r.setEndTs(endTs);
        r.setSlippagePct(BigDecimal.valueOf(slippagePct));
        r.setStatus("RUNNING");
        r.setCreatedAt(LocalDateTime.now());
        backtestResultMapper.insert(r);

        // 策略参数（与实盘同一来源）
        Map<String, Map<String, Object>> params = userService.getStrategyParams(userId);
        Map<String, Object> p = params.get(sk);
        if (p == null) {
            r.setStatus("FAILED");
            backtestResultMapper.updateById(r);
            return r;
        }

        // 拉取落库K线
        List<MarketData> bars = marketDataMapper.queryRange(symbol, interval, startTs, endTs);
        List<MarketData> closes = bars;
        int n = closes.size();
        if (n < 60) {
            log.warn("[backtest] {} {} {} 样本不足({}根<60), 跳过", sk, symbol, interval, n);
            r.setStatus("FAILED");
            backtestResultMapper.updateById(r);
            return r;
        }

        try {
            double openPosPrice = 0;
            String dir = null;           // 当前持仓方向(LONG/SHORT)
            int totalSignals = 0;
            double equity = 0, peakEquity = 0, maxDrawdown = 0;
            int totalTrades = 0, winTrades = 0, loseTrades = 0;
            double sumPnl = 0, sumWin = 0, sumLose = 0;
            List<Double> pnlSeries = new ArrayList<>();

            double tp = getParamDouble(p, "tp_ratio");   // 如 800(%) -> 需 /100 转倍数
            double sl = getParamDouble(p, "sl_ratio");   // 如 -20(%) -> /100
            double tpMult = tp / 100.0;
            double slMult = sl / 100.0;

            // 逐K线回放：用"截至当前根"的K线构造 tick，调用与实盘一致的信号函数
            for (int i = 59; i < n; i++) {   // 至少需要60根起步，保证EMA等指标有种子
                MarketData bar = bars.get(i);
                Map<String, Object> tick = new HashMap<>();
                tick.put("symbol", symbol);
                tick.put("lastPrice", bar.getClose().doubleValue());
                tick.put("openPrice", bars.get(i - 1).getClose().doubleValue());
                tick.put("highPrice", bar.getHigh().doubleValue());
                tick.put("lowPrice", bar.getLow().doubleValue());
                tick.put("quoteVolume", bar.getQuoteVolume() == null ? 0 : bar.getQuoteVolume().doubleValue());
                tick.put("priceChangePercent", pct(bar.getClose().doubleValue(), bars.get(i - 1).getClose().doubleValue()));

                // 简化 tick 内的前收盘价/高点（信号依赖的字段以真实K线填充）
                tick.put("open", bars.get(i - 1).getClose().doubleValue());

                // 1) 若已持仓，先判断止盈止损（模拟平仓）
                if (dir != null) {
                    double last = bar.getClose().doubleValue();
                    double pnlRatio = dir.equals("LONG")
                            ? pct(last, openPosPrice) : pct(openPosPrice, last);
                    boolean close = false;
                    String reason = null;
                    if (slMult < 0 && pnlRatio <= slMult) { close = true; reason = "sl"; }
                    else if (tpMult > 0 && pnlRatio >= tpMult) { close = true; reason = "tp"; }
                    if (close) {
                        double pnl = BT_MARGIN * pnlRatio / 100.0 - BT_MARGIN * slippagePct / 100.0;
                        equity += pnl;
                        peakEquity = Math.max(peakEquity, equity);
                        maxDrawdown = Math.max(maxDrawdown, peakEquity > 0 ? (peakEquity - equity) / peakEquity * 100 : 0);
                        totalTrades++;
                        if (pnl > 0) { winTrades++; sumWin += pnl; } else { loseTrades++; sumLose += pnl; }
                        sumPnl += pnl;
                        dir = null;
                    }
                }

                // 2) 空仓则检测信号（与实盘同一 check()）
                if (dir == null) {
                    Map<String, Object> sig;
                    try {
                        sig = strategyDetector.check(sk, symbol, tick, p);
                    } catch (Exception e) {
                        sig = null;
                    }
                    if (sig != null) {
                        totalSignals++;
                        openPosPrice = bar.getClose().doubleValue();
                        dir = (String) sig.get("direction");
                    }
                }
            }

            // 写结果
            double winRate = totalTrades > 0 ? (double) winTrades / totalTrades : 0;
            double avgWin = winTrades > 0 ? sumWin / winTrades : 0;
            double avgLose = loseTrades > 0 ? sumLose / loseTrades : 0;

            r.setTotalSignals(totalSignals);
            r.setTotalTrades(totalTrades);
            r.setWinTrades(winTrades);
            r.setLoseTrades(loseTrades);
            r.setWinRate(BigDecimal.valueOf(winRate).setScale(4, RoundingMode.HALF_UP));
            r.setTotalPnl(BigDecimal.valueOf(sumPnl));
            r.setMaxDrawdown(BigDecimal.valueOf(maxDrawdown).setScale(4, RoundingMode.HALF_UP));
            r.setAvgWin(BigDecimal.valueOf(avgWin));
            r.setAvgLose(BigDecimal.valueOf(avgLose));
            if (avgWin != avgWin) r.setAvgWin(BigDecimal.ZERO); // NaN 兜底
            if (avgLose != avgLose) r.setAvgLose(BigDecimal.ZERO);
            r.setParamsJson(objectMapper.writeValueAsString(p));
            r.setSharpe(BigDecimal.ZERO); // 日频Sharpe需日收益序列，回测交互入口计算，此处占位
            r.setStatus("COMPLETED");
            backtestResultMapper.updateById(r);

            log.info("[backtest] {} {} {} 完成: 信号{} 成交{} 胜率{}% 盈亏{}U 回撤{}%",
                    sk, symbol, interval, totalSignals, totalTrades,
                    String.format("%.1f", winRate * 100),
                    String.format("%.2f", sumPnl), String.format("%.2f", maxDrawdown));
            return r;

        } catch (Exception e) {
            log.error("[backtest] {} {} {} 回测异常: {}", sk, symbol, interval, e.getMessage());
            r.setStatus("FAILED");
            backtestResultMapper.updateById(r);
            return r;
        }
    }
}
