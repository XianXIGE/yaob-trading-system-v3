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
}
