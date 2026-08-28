package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 交易引擎纯静态计算工具集。
 * 从 TradeEngineService（God Class）拆分：无状态、无 Spring 依赖、可单测复用。
 * 供 StrategyDetectorService 与 TradeEngineService 等共同调用。
 */
public final class TradeMath {

    private TradeMath() {
    }

    public static double getDouble(Map<String, Object> map, Object key) {
        Object v = map.get(String.valueOf(key));
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    public static double getParamDouble(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    public static double pct(double a, double b) {
        if (b == 0) return 0.0;
        return (a - b) / b * 100.0;
    }

    /** 计算RSI: 用K线收盘价数组 */
    public static double calcRSI(JsonNode klines, int period) {
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

    /** 计算EMA: 用前N根均值做种子, 逐根迭代 */
    public static double emaVal(double[] closes, int period) {
        if (closes.length < period) return closes[closes.length - 1];
        double k = 2.0 / (period + 1);
        double e = 0;
        int start = closes.length - period;
        for (int i = start; i < start + period && i < closes.length; i++) {
            e = (e == 0) ? closes[i] : (closes[i] - e) * k + e;
        }
        for (int i = start + period; i < closes.length; i++) {
            e = (closes[i] - e) * k + e;
        }
        return e;
    }

    public static String normSymbol(String sym) {
        if (sym != null && sym.endsWith("USDT")) {
            return sym.substring(0, sym.length() - 4) + "/USDT";
        }
        return sym;
    }

    /**
     * 规范为币安全原始交易对格式（去掉斜杠）："BTC/USDT" / "btcusdt" -> "BTCUSDT".
     */
    public static String rawSymbol(String sym) {
        if (sym == null) return null;
        return sym.replace("/", "").toUpperCase();
    }

    // ==================== ATR 波动率计算（妖币系统优化 P0-3）====================

    /**
     * 计算 ATR (Average True Range)，Wilder 平滑法。
     * 输入每个K线的 [high, low, close] 三数组（同长度），返回长度 = closes.length。
     * 前 period 个元素为 0（种子期），第 period 及之后为有效 ATR。
     * <p>妖币高波动：固定百分比止损会被正常波动扫损，需用 ATR 自适应止损(1.5-2×ATR)。
     */
    public static double[] atr(double[] highs, double[] lows, double[] closes, int period) {
        int n = highs.length;
        double[] trs = new double[n];
        double[] out = new double[n];
        if (n == 0) return out;
        for (int i = 0; i < n; i++) {
            double h = highs[i], l = lows[i], c = (i == 0) ? closes[i] : closes[i - 1];
            double tr = Math.max(h - l, Math.max(Math.abs(h - c), Math.abs(l - c)));
            trs[i] = tr;
        }
        if (n < period) return out;
        double seed = 0;
        for (int i = 0; i < period; i++) seed += trs[i];
        out[period - 1] = seed / period;
        for (int i = period; i < n; i++) {
            out[i] = (out[i - 1] * (period - 1) + trs[i]) / period;
        }
        return out;
    }

    /** 便利方法：直接返回最新一个 ATR 值（无有效值时返回默认）。 */
    public static double atrLast(double[] highs, double[] lows, double[] closes, int period, double defaultValue) {
        double[] a = atr(highs, lows, closes, period);
        if (a.length == 0) return defaultValue;
        return a[a.length - 1];
    }

    /**
     * 波动率状态判定（P2-7 状态机整合器前置）：
     * 用 ATR% 相对阈值判定当前市场处于 趋势/震荡/极端避险 三态之一。
     * @param atrPct  当前 ATR / 价格 的百分比(如 0.03 表示 3%)
     * @param highPct ATR% 超此值视为极端避险态(如 0.08 => 8%)
     * @param lowPct  低于此值视为低波动震荡态(如 0.02)
     */
    public static String marketState(double atrPct, double highPct, double lowPct) {
        if (atrPct >= highPct) return "RISK_OFF";   // 波动率爆表 -> 避险清仓
        if (atrPct <= lowPct) return "SIDEWAYS";     // 低波动 -> 网格/均值回归
        return "TREND";                              // 中波动 -> 趋势跟随
    }
}
