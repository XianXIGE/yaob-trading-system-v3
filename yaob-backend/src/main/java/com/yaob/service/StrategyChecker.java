package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 策略 A-F 信号检测（从 TradeEngineService 拆分）
 */
@Slf4j
@Service
public class StrategyChecker {

    @Autowired
    private MarketDataService marketData;

    /** 统一入口 */
    public Map<String, Object> check(String strategy, String sym, Map<String, Object> tick, Map<String, Object> p) {
        if (strategy == null || p == null || tick == null) return null;
        try {
            return switch (strategy) {
                case "A" -> checkA(tick, p);
                case "B" -> checkB(tick, p);
                case "C" -> checkC(tick, p);
                case "D" -> checkD(sym, tick, p);
                case "E" -> checkE(sym, tick, p);
                case "F" -> checkF(sym, tick, p);
                default -> null;
            };
        } catch (Exception e) {
            log.debug("[strategy] {} {} fail: {}", strategy, sym, e.getMessage());
            return null;
        }
    }

    // ==================== Strategy Checks (v2 1:1 translation) ====================

    private Map<String, Object> checkA(Map<String, Object> tick, Map<String, Object> p) {
        // 做空(A): 24h涨幅在[gain_min, gain_max] + 成交额达标
        double g24 = getDouble(tick, "priceChangePercent"); // 已是百分比，如 38.5
        double qv = getDouble(tick, "quoteVolume");
        double gainMin = getParamDouble(p, "gain_min");
        double gainMax = getParamDouble(p, "gain_max");
        double volMin = getParamDouble(p, "vol_min");
        if (gainMin <= g24 && g24 <= gainMax && qv >= volMin) {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("strategy", "A");
            sig.put("direction", "SHORT");
            sig.put("reason", String.format("24h涨幅%.1f%%", g24));
            sig.put("threshold_ratio", g24);
            return sig;
        }
        return null;
    }

    private Map<String, Object> checkB(Map<String, Object> tick, Map<String, Object> p) {
        // 做空(B): 当日涨幅>=N% + 成交额达标
        double o = getDouble(tick, "openPrice");
        double c = getDouble(tick, "lastPrice");
        double g = pct(c, o); // 已是百分比，如 38.5
        double gainThreshold = getParamDouble(p, "gain_threshold");
        double volMin = getParamDouble(p, "vol_min");
        if (g >= gainThreshold && getDouble(tick, "quoteVolume") >= volMin) {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("strategy", "B");
            sig.put("direction", "SHORT");
            sig.put("reason", String.format("当日涨幅%.1f%%", g));
            sig.put("threshold_ratio", g);
            return sig;
        }
        return null;
    }

    private Map<String, Object> checkC(Map<String, Object> tick, Map<String, Object> p) {
        // 做多(C): 从当日高点回撤>=M% + 成交额达标
        double hi = getDouble(tick, "highPrice");
        double c = getDouble(tick, "lastPrice");
        double drop = pct(c, hi); // 负值=回撤，已是百分比
        double dropThreshold = getParamDouble(p, "drop_threshold");
        double volMin = getParamDouble(p, "vol_min");
        if (hi > 0 && drop <= -Math.abs(dropThreshold) && getDouble(tick, "quoteVolume") >= volMin) {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("strategy", "C");
            sig.put("direction", "LONG");
            sig.put("reason", String.format("自高点回撤%.1f%%", Math.abs(drop)));
            sig.put("threshold_ratio", drop);
            return sig;
        }
        return null;
    }

    private Map<String, Object> checkD(String sym, Map<String, Object> tick, Map<String, Object> p) {
        // 做空(D): N分钟涨幅>=N% (需1m k线)
        int w = (int) getParamDouble(p, "window_minutes");
        try {
            JsonNode k = marketData.getKlines(sym, "1m", w + 1);
            if (k == null || !k.isArray() || k.size() < w + 1) return null;
            double p0 = k.get(k.size() - w - 1).get(4).asDouble(); // close price
            double p1 = k.get(k.size() - 1).get(4).asDouble();
            double g = pct(p1, p0); // 已是百分比
            double gainThreshold = getParamDouble(p, "gain_threshold");
            double volMin = getParamDouble(p, "vol_min");
            if (g >= gainThreshold && getDouble(tick, "quoteVolume") >= volMin) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "D");
                sig.put("direction", "SHORT");
                sig.put("reason", String.format("%d分钟涨幅%.1f%%", w, g));
                sig.put("threshold_ratio", g);
                return sig;
            }
        } catch (Exception e) {
            // klines 获取失败, skip
        }
        return null;
    }

    private Map<String, Object> checkE(String sym, Map<String, Object> tick, Map<String, Object> p) {
        // 做多(E): 强趋势回踩 - 30天涨幅>100%, EMA50上方, 回撤20%-40%至0.618 Fib
        double gain30dMin = getParamDouble(p, "gain_30d_min"); // 30天涨幅下限(%)
        int emaPeriod = (int) getParamDouble(p, "ema_period"); // EMA周期
        double pullbackMin = getParamDouble(p, "pullback_min"); // 回调下限(%)
        double pullbackMax = getParamDouble(p, "pullback_max"); // 回调上限(%)
        double fibEntry = getParamDouble(p, "fib_entry"); // 斐波那契入场位(0.618)
        double volumeMult = getParamDouble(p, "volume_mult"); // 成交量放大倍数
        double rsiThreshold = getParamDouble(p, "rsi_threshold"); // RSI回升阈值
        double volMin = getParamDouble(p, "vol_min"); // 24h成交额下限
        double cur = getDouble(tick, "lastPrice");
        double qv = getDouble(tick, "quoteVolume");
        if (qv < volMin) return null;

        try {
            // 拉30天日线K线计算涨幅、EMA、高低点
            JsonNode k30d = marketData.getKlines(sym, "1d", 30 + emaPeriod + 5);
            if (k30d == null || !k30d.isArray() || k30d.size() < emaPeriod + 5) return null;

            // 30天涨幅 = (最新收盘 / 30天前收盘 - 1) * 100
            double price30dAgo = k30d.get(k30d.size() - 31).get(4).asDouble();
            double gain30d = pct(cur, price30dAgo);
            if (gain30d < gain30dMin) return null;

            // EMA计算
            double ema = 0;
            double mult = 2.0 / (emaPeriod + 1);
            // 用前emaPeriod根的均值作为EMA种子
            int startIdx = k30d.size() - emaPeriod;
            for (int i = startIdx; i < startIdx + emaPeriod && i < k30d.size(); i++) {
                double close = k30d.get(i).get(4).asDouble();
                ema = (ema == 0) ? close : (close - ema) * mult + ema;
            }
            // EMA50上方
            if (cur < ema) return null;

            // 30天高低点
            double hi30d = 0, lo30d = Double.MAX_VALUE;
            for (int i = k30d.size() - 30; i < k30d.size(); i++) {
                double h = k30d.get(i).get(2).asDouble();
                double l = k30d.get(i).get(3).asDouble();
                if (h > hi30d) hi30d = h;
                if (l < lo30d) lo30d = l;
            }
            double rng = hi30d - lo30d;
            if (rng <= 0) return null;

            // 回撤幅度 = (最高点 - 当前价) / 最高点 * 100
            double pullback = pct(hi30d - cur, hi30d);
            if (pullback < pullbackMin || pullback > pullbackMax) return null;

            // 斐波那契入场位: 0.618回撤 = hi - rng * 0.618
            double fibPrice = hi30d - rng * fibEntry;
            double fibDist = Math.abs(cur - fibPrice) / fibPrice * 100;
            if (fibDist > 2.0) return null; // 价格需在斐波那契位2%范围内

            // 成交量放大: 最近5天平均成交量 vs 前5天
            if (k30d.size() >= 15) {
                double recentVol = 0, prevVol = 0;
                for (int i = k30d.size() - 5; i < k30d.size(); i++) recentVol += k30d.get(i).get(5).asDouble();
                for (int i = k30d.size() - 10; i < k30d.size() - 5; i++) prevVol += k30d.get(i).get(5).asDouble();
                recentVol /= 5; prevVol /= 5;
                if (prevVol > 0 && recentVol / prevVol < volumeMult) return null;
            }

            // RSI(14) 回升阈值
            if (rsiThreshold > 0 && k30d.size() >= 15) {
                double rsi = calcRSI(k30d, 14);
                if (rsi < rsiThreshold) return null;
            }

            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("strategy", "E");
            sig.put("direction", "LONG");
            sig.put("reason", String.format("30天涨幅%.0f%% 回撤%.1f%% 至Fib %.3f", gain30d, pullback, fibEntry));
            sig.put("threshold_ratio", pullback);
            return sig;
        } catch (Exception e) {
            // klines获取失败, skip
        }
        return null;
    }

    private Map<String, Object> checkF(String sym, Map<String, Object> tick, Map<String, Object> p) {
        // 斐波那契双向: 用lookback_hours的K线高低点算区间, 做多回撤/做空反弹 + 容差 + 成交额达标
        int lookbackHours = (int) getParamDouble(p, "lookback_hours");
        double volMin = getParamDouble(p, "vol_min");
        double cur = getDouble(tick, "lastPrice");
        double qv = getDouble(tick, "quoteVolume");
        if (qv < volMin) return null;

        try {
            // 用1小时K线拉 lookback_hours 根
            JsonNode k = marketData.getKlines(sym, "1h", lookbackHours);
            if (k == null || !k.isArray() || k.size() < lookbackHours) return null;

            double hi = 0, lo = Double.MAX_VALUE;
            for (int i = 0; i < k.size(); i++) {
                double h = k.get(i).get(2).asDouble();
                double l = k.get(i).get(3).asDouble();
                if (h > hi) hi = h;
                if (l < lo) lo = l;
            }
            double rng = hi - lo;
            if (rng <= 0) return null;

            double fl = getParamDouble(p, "fib_long");
            double fs = getParamDouble(p, "fib_short");
            // 容差: 按区间百分比
            double tol = getParamDouble(p, "tolerance_ratio") / 100.0 * rng;

            // 做空: 价格反弹至 fib_short 位 (lo + rng * fs)
            double shortPrice = lo + rng * fs;
            if (Math.abs(cur - shortPrice) <= tol) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "F");
                sig.put("direction", "SHORT");
                sig.put("reason", String.format("斐波那契做空(反弹至%.1f%%, 阻力%.4f)", fs * 100, shortPrice));
                sig.put("threshold_ratio", fs * 100);
                return sig;
            }
            // 做多: 价格回撤至 fib_long 位 (hi - rng * fl)
            double longPrice = hi - rng * fl;
            if (Math.abs(cur - longPrice) <= tol) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "F");
                sig.put("direction", "LONG");
                sig.put("reason", String.format("斐波那契做多(回撤至%.1f%%, 支撑%.4f)", fl * 100, longPrice));
                sig.put("threshold_ratio", fl * 100);
                return sig;
            }
        } catch (Exception e) {
            // klines获取失败, skip
        }
        return null;
    }


    private static double getDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private static double getParamDouble(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private static double pct(double a, double b) {
        if (b == 0) return 0.0;
        return (a - b) / b * 100.0;
    }

    private static double calcRSI(JsonNode klines, int period) {
        if (klines == null || klines.size() < period + 1) return 50.0;
        double gainSum = 0, lossSum = 0;
        int start = klines.size() - period - 1;
        for (int i = start; i < start + period; i++) {
            double prev = klines.get(i).get(4).asDouble();
            double cur = klines.get(i + 1).get(4).asDouble();
            double diff = cur - prev;
            if (diff >= 0) gainSum += diff;
            else lossSum -= diff;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
