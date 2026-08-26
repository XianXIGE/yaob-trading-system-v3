package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.yaob.service.TradeMath.*;

/**
 * 策略检测器：七大交易策略（A-G）的信号检测 + BTC 大盘趋势过滤器 + 纯计算工具。
 *
 * 从原 TradeEngineService（God Class）拆分而来，职责单一：输入该币的 24h ticker 与策略参数，
 * 输出命中的交易信号（direction/reason/threshold_ratio）；无状态，不触碰任何持仓/账户/数据库。
 * 依赖仅 BinanceFapiService（拉 K 线），可独立测试与复用。
 */
@Slf4j
@Service
public class StrategyDetectorService {

    @Autowired
    private BinanceFapiService fapi;

    // BTC趋势缓存: {timestamp, aboveMA20} —— 每次扫描刷新一次
    private volatile boolean btcAboveMA20 = true;
    private volatile long btcTrendTs = 0;

    /** 刷新BTC趋势状态: 拉BTCUSDT 4h K线120根(≈20天), 判断现价是否在MA120上方 */
    public void refreshBtcTrend() {
        try {
            JsonNode k = fapi.klines("BTCUSDT", "4h", 130);
            if (k == null || !k.isArray() || k.size() < 120) return;
            double sum = 0;
            for (int i = k.size() - 120; i < k.size(); i++) {
                sum += k.get(i).get(4).asDouble(); // 收盘价
            }
            double ma120 = sum / 120;
            double cur = k.get(k.size() - 1).get(4).asDouble();
            btcAboveMA20 = cur > ma120;
            btcTrendTs = System.currentTimeMillis();
            log.info("[BTC趋势] 现价{} MA120(4h){} -> {}", String.format("%.0f", cur), String.format("%.0f", ma120), btcAboveMA20 ? "强势(线上)" : "弱势(线下)");
        } catch (Exception e) {
            log.warn("[BTC趋势] 获取失败, 保持上次状态: {}", e.getMessage());
        }
    }

    /** BTC是否处于上升趋势(现价>MA120_4h) */
    public boolean isBtcBullish() {
        // 缓存有效期30分钟
        if (System.currentTimeMillis() - btcTrendTs > 30 * 60 * 1000) {
            refreshBtcTrend();
        }
        return btcAboveMA20;
    }

    /**
     * 按策略 key 检测单个币的信号。返回命中信号 Map 或 null。
     * @param sym  币安全原始交易对（如 "BTCUSDT"）
     * @param tick 该币的 24h ticker
     * @param p    该策略参数
     */
    public Map<String, Object> check(String key, String sym, Map<String, Object> tick, Map<String, Object> p) {
        switch (key) {
            case "A": return checkA(tick, p);
            case "B": return checkB(tick, p);
            case "C": return checkC(tick, p);
            case "D": return checkD(sym, tick, p);
            case "E": return checkE(sym, tick, p);
            case "F": return checkF(sym, tick, p);
            case "G": return checkG(sym, tick, p);
            default: return null;
        }
    }

    private Map<String, Object> checkA(Map<String, Object> tick, Map<String, Object> p) {
        // 做空(A): 24h涨幅在[gain_min, gain_max] + 成交额达标
        // [v3.4 优化] BTC强势时禁止做空(顺大盘), need_btc_weak 缺省 true(默认过滤)
        if (getParamBool(p, "need_btc_weak", true) && isBtcBullish()) return null;
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
        // [v3.4 优化] BTC强势时禁止做空(顺大盘), need_btc_weak 缺省 true
        if (getParamBool(p, "need_btc_weak", true) && isBtcBullish()) return null;
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
        // [v3.4 优化] BTC强势时禁止做空(顺大盘), need_btc_weak 缺省 true
        if (getParamBool(p, "need_btc_weak", true) && isBtcBullish()) return null;
        int w = (int) getParamDouble(p, "window_minutes");
        try {
            JsonNode k = fapi.klines(sym, "1m", w + 1);
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
        // [v3.4 优化] BTC弱势时禁止做多(顺大盘), need_btc_strong 缺省 true
        if (getParamBool(p, "need_btc_strong", true) && !isBtcBullish()) return null;
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
            JsonNode k30d = fapi.klines(sym, "1d", 30 + emaPeriod + 5);
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
            JsonNode k = fapi.klines(sym, "1h", lookbackHours);
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

            // 大盘过滤器: BTC强势时只做多, BTC弱势时只做空 (顺大盘方向)
            boolean btcBull = isBtcBullish();

            // 做空: 价格反弹至 fib_short 位 (lo + rng * fs) -- 仅BTC弱势时触发
            double shortPrice = lo + rng * fs;
            if (Math.abs(cur - shortPrice) <= tol && !btcBull) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "F");
                sig.put("direction", "SHORT");
                sig.put("reason", String.format("斐波那契做空(反弹至%.1f%%, 阻力%.4f) [BTC弱势]", fs * 100, shortPrice));
                sig.put("threshold_ratio", fs * 100);
                return sig;
            }
            // 做多: 价格回撤至 fib_long 位 (hi - rng * fl) -- 仅BTC强势时触发
            double longPrice = hi - rng * fl;
            if (Math.abs(cur - longPrice) <= tol && btcBull) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "F");
                sig.put("direction", "LONG");
                sig.put("reason", String.format("斐波那契做多(回撤至%.1f%%, 支撑%.4f) [BTC强势]", fl * 100, longPrice));
                sig.put("threshold_ratio", fl * 100);
                return sig;
            }
        } catch (Exception e) {
            // klines获取失败, skip
        }
        return null;
    }

    private Map<String, Object> checkG(String sym, Map<String, Object> tick, Map<String, Object> p) {
        // G: 三重过滤 -- 1h EMA20/EMA60排列(趋势) + 量比(量价) + EMA60为多空分水岭(关键均线)
        // 6个子信号: 关注做多/回调做多/超跌反弹/关注做空/反弹做空/冲高回落做空
        double volMin = getParamDouble(p, "vol_min");
        int emaShort = (int) getParamDouble(p, "ema_short");   // 20
        int emaLong = (int) getParamDouble(p, "ema_long");     // 60
        double volRatioMin = getParamDouble(p, "vol_ratio_min"); // 1.3
        double rsiOversold = getParamDouble(p, "rsi_oversold");  // 32
        double wickBodyRatio = getParamDouble(p, "wick_body_ratio"); // 1.5
        double cur = getDouble(tick, "lastPrice");
        double qv = getDouble(tick, "quoteVolume");
        if (qv < volMin) return null;

        // [v3.5 修复] G 策略补齐大盘顺趋势过滤(v3.4 只给 A-F 加了 need_btc_*，漏了 G)：
        // 实盘归因显示 G 逆势单是延续亏损主源（强势币 ONG/PROM 被反复摸顶做空），
        // 与 A-F 同口径：need_btc_weak(缺省 true)=BTC强势时禁做空；need_btc_strong(缺省 true)=BTC弱势时禁做多。
        boolean shortNeedsWeak  = getParamBool(p, "need_btc_weak", true);
        boolean longNeedsStrong = getParamBool(p, "need_btc_strong", true);
        boolean btcBullish = isBtcBullish();

        // [v3.5] 币自身大周期趋势闸门(4h EMA50 + “站稳”缓冲带)：
        // 仅靠大盘不够——币可自身强势而大盘未必（ONG 1h/4h 单边强，仍被摸顶做空）。
        // 做多需现价站稳 4h EMA50 上方(>EMA50*1.03)；做空需现价站稳其下方(<EMA50*0.97)。
        // “站稳缓冲”±3% 避免贴均线反复穿越的假方向。
        boolean coinBull4h;
        try {
            JsonNode k4 = fapi.klines(sym, "4h", 60);
            if (k4 != null && k4.isArray() && k4.size() >= 50) {
                double[] c4 = new double[k4.size()];
                for (int i = 0; i < k4.size(); i++) c4[i] = k4.get(i).get(4).asDouble();
                double e50_4h = emaVal(c4, 50);
                coinBull4h = cur > e50_4h * 1.03;
            } else {
                coinBull4h = true; // 数据不足时保守放行(不误伤)
            }
        } catch (Exception e) {
            coinBull4h = true; // 拉取失败保守放行
        }

        try {
            JsonNode kl = fapi.klines(sym, "1h", 120);
            if (kl == null || !kl.isArray() || kl.size() < emaLong + 10) return null;

            // 收盘价数组
            double[] closes = new double[kl.size()];
            double[] opens = new double[kl.size()];
            double[] highs = new double[kl.size()];
            double[] lows = new double[kl.size()];
            double[] vols = new double[kl.size()];
            for (int i = 0; i < kl.size(); i++) {
                closes[i] = kl.get(i).get(4).asDouble();
                opens[i] = kl.get(i).get(1).asDouble();
                highs[i] = kl.get(i).get(2).asDouble();
                lows[i] = kl.get(i).get(3).asDouble();
                vols[i] = kl.get(i).get(5).asDouble();
            }

            // EMA计算
            double e20 = emaVal(closes, emaShort);
            double e60 = emaVal(closes, emaLong);
            double r = calcRSI(kl, 14);

            // 量比: 最后一根1h量 vs 前20根均量
            double avgVol = 0;
            for (int i = kl.size() - 21; i < kl.size() - 1; i++) avgVol += vols[i];
            avgVol /= 20;
            double volRatio = avgVol > 0 ? vols[vols.length - 1] / avgVol : 1.0;

            // ATR(14)
            double atrSum = 0;
            for (int i = kl.size() - 14; i < kl.size(); i++) {
                double tr = Math.max(highs[i] - lows[i],
                        Math.max(Math.abs(highs[i] - closes[i - 1]), Math.abs(lows[i] - closes[i - 1])));
                atrSum += tr;
            }
            double atr = atrSum / 14;

            // 24h高低点
            double hi24 = 0, lo24 = Double.MAX_VALUE;
            for (int i = kl.size() - 24; i < kl.size(); i++) {
                if (highs[i] > hi24) hi24 = highs[i];
                if (lows[i] < lo24) lo24 = lows[i];
            }

            // 最近已收盘K线(倒数第二根, 最后一根可能未收盘)
            int li = kl.size() - 2;
            double lastClose = closes[li];
            double lastOpen = opens[li];
            double lastHigh = highs[li];
            double lastLow = lows[li];
            double lastVol = vols[li];
            double prevAvgVol = 0;
            for (int i = li - 20; i < li; i++) prevAvgVol += vols[i];
            prevAvgVol /= 20;
            double lastVolRatio = prevAvgVol > 0 ? lastVol / prevAvgVol : 1.0;

            // 判断6种子信号(按优先级)
            boolean bullish = e20 > e60 && cur > e60;
            boolean bearish = e20 < e60 && cur < e60;
            boolean isGreenBar = lastClose > lastOpen; // 阳线
            boolean isRedBar = lastClose < lastOpen;   // 阴线

            // 双重闸门最终方向许可：
            //   做多需【BTC强势(且 need_btc_strong 开启)】 AND 【币4h站稳强势】
            //   做空需【BTC弱势(且 need_btc_weak 开启)】  AND 【币4h站稳弱势】
            // 彻底杜绝强势币被反向摸顶做空(如 ONG/PROM)。
            boolean longAllowed  = (!longNeedsStrong || btcBullish) && coinBull4h;
            boolean shortAllowed = (!shortNeedsWeak  || !btcBullish) && !coinBull4h;

            String subSignal = null;
            String direction = null;
            String defense = null;
            String target = null;
            String reason = null;

            // A. 关注做多: close>EMA20>EMA60 + 放量(量比>=volRatioMin) + 阳线 (需做多许可)
            if (longAllowed && cur > e20 && e20 > e60 && lastVolRatio >= volRatioMin && isGreenBar) {
                subSignal = "关注做多";
                direction = "LONG";
                defense = String.format("EMA20 %.4g / EMA60 %.4g", e20, e60);
                target = String.format("%.4g", hi24);
                reason = String.format("顺势放量阳线(量比%.1f) 站上EMA20>EMA60", lastVolRatio);
            }
            // B. 回调做多: 趋势偏强(EMA20>EMA60) + 缩量回调至EMA20下方 (需做多许可)
            else if (longAllowed && bullish && cur < e20 && lastVolRatio < 1.0) {
                subSignal = "回调做多";
                direction = "LONG";
                defense = String.format("EMA60 %.4g", e60);
                target = String.format("%.4g", hi24);
                reason = String.format("趋势偏强+缩量回调(量比%.1f) 至EMA20下方", lastVolRatio);
            }
            // C. 超跌反弹: RSI<oversold + 转阳 + 深度偏离EMA60 (需做多许可，弱势禁抄底接飞刀)
            else if (longAllowed && r < rsiOversold && isGreenBar && cur < e60 * 0.92) {
                subSignal = "超跌反弹";
                direction = "LONG";
                defense = String.format("%.4g(再创新低无条件走)", lo24);
                target = String.format("EMA60 %.4g", e60);
                reason = String.format("RSI=%.0f 超跌转阳 偏离EMA60 %.1f%%", r, (cur / e60 - 1) * 100);
            }
            // D. 关注做空: close<EMA20<EMA60 + 放量(量比>=volRatioMin) + 阴线 (需做空许可)
            else if (shortAllowed && cur < e20 && e20 < e60 && lastVolRatio >= volRatioMin && isRedBar) {
                subSignal = "关注做空";
                direction = "SHORT";
                defense = String.format("EMA20 %.4g / EMA60 %.4g", e20, e60);
                target = String.format("%.4g", lo24);
                reason = String.format("顺势放量阴线(量比%.1f) 跌破EMA20<EMA60", lastVolRatio);
            }
            // E. 反弹做空: 趋势偏空(EMA20<EMA60) + 缩量反弹至EMA20上方 (需做空许可)
            else if (shortAllowed && bearish && cur > e20 && lastVolRatio < 1.0) {
                subSignal = "反弹做空";
                direction = "SHORT";
                defense = String.format("EMA60 %.4g", e60);
                target = String.format("%.4g", lo24);
                reason = String.format("趋势偏空+缩量反弹(量比%.1f) 至EMA20上方", lastVolRatio);
            }
            // F. 冲高回落做空: 上影线长 + 收盘阴线 + 缩量 (需做空许可，强势币禁摸顶)
            else if (shortAllowed && lastHigh - lastClose >= wickBodyRatio * Math.max(lastClose - lastOpen, 1e-9)
                    && isRedBar && lastVolRatio < 1.0) {
                subSignal = "冲高回落";
                direction = "SHORT";
                defense = String.format("EMA20 %.4g(站上离场)", e20);
                target = String.format("%.4g", lo24);
                reason = String.format("长上影+阴线+缩量(量比%.1f)", lastVolRatio);
            }
            // 仅当命中一个子信号才返回；否则返回null（候选池里该币归为"未触发"）
            if (subSignal != null) {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("strategy", "G");
                sig.put("direction", direction);
                sig.put("reason", subSignal + ":" + reason);
                sig.put("defense", defense);
                sig.put("target", target);

                // [v3.6] 数值化参考位（动态计算，替代固定百分比止盈止损）：
                //   防守位 = 结构性止损位；目标位 = 结构性目标位；
                //   保护位 = 入场后移动止损基准（保本）；回踩减仓位 = 减仓观察位。
                // 做多信号：
                //   防守位取“前低lo24”与“EMA60”中离现价较近者（止损不过深）
                //   目标位取 24h 高 hi24（有成交量确认时更可靠）
                // 做空信号：
                //   防守位取“前高hi24”与“EMA60”中离现价较近者
                //   目标位取 24h 低 lo24
                boolean isLong = "LONG".equals(direction);
                double defensePrice;
                double targetPrice;
                if (isLong) {
                    // 防守：lo24 与 EMA60 取较高者(离现价更近)”
                    double cand1 = lo24;      // 创新低无条件走
                    double cand2 = Math.min(e60, cur * 0.92); // 超跌反弹防守用 EMA60
                    defensePrice = Math.max(cand1, cand2);
                    // 目标：hi24 为结构性压力（有量能确认更可靠）
                    targetPrice = hi24;
                } else {
                    // 防守：hi24 与 EMA60 取较低者(离现价更近)
                    double cand1 = hi24;
                    double cand2 = e60;
                    defensePrice = Math.min(cand1, cand2);
                    // 目标：lo24 为结构性支撑
                    targetPrice = lo24;
                }
                // 有效性校验：防守/目标必须与方向一致且远离现价，否则退化用 ATR 缓冲
                if (isLong) {
                    if (defensePrice <= 0 || defensePrice >= cur) defensePrice = cur * (1 - 0.015); // 兜底-1.5%
                    if (targetPrice <= cur) targetPrice = cur * (1 + 0.06);                           // 兜底+6%
                } else {
                    if (defensePrice <= 0 || defensePrice <= cur) defensePrice = cur * (1 + 0.015); // 兜底+1.5%
                    if (targetPrice >= cur) targetPrice = cur * (1 - 0.06);                           // 兜底-6%
                }
                // 保护位：入场后移动止损的初始基准（保本±小额缓冲），平仓引擎按浮盈上移
                double protectPrice = isLong ? cur * (1 - 0.005) : cur * (1 + 0.005);
                // 回踩减仓位：多买回踩 EMA20、空反弹至 EMA20
                double reducePrice = isLong ? Math.min(e20, cur) : Math.max(e20, cur);

                sig.put("defense_price", round4(defensePrice));
                sig.put("target_price", round4(targetPrice));
                sig.put("protect_price", round4(protectPrice));
                sig.put("reduce_price", round4(reducePrice));
                return sig;
            }
        } catch (Exception e) {
            // klines获取失败, skip
        }
        return null;
    }

    /** [v3.6] 保留4位小数(double -> double) */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
    /** [v3.4] 读取布尔参数, 缺省返回 defVal */
    private boolean getParamBool(Map<String, Object> p, String key, boolean defVal) {
        Object v = p.get(key);
        if (v == null) return defVal;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
