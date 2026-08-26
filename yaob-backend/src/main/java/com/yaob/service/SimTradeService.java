package com.yaob.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yaob.entity.OpenPosition;
import com.yaob.entity.TradeHistory;
import com.yaob.mapper.OpenPositionMapper;
import com.yaob.mapper.TradeHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B1 实时跟单模拟盘：为 G 策略跑"完全真实但不下真单"的模拟交易。
 *
 * 复用 StrategyDetectorService.checkG 的完整信号逻辑（含 v3.6 动态参考位），
 * 每轮真实行情扫描后，对独立模拟账户(sim_g_paper)模拟开仓/平仓：
 *   - 检测到 G 信号 → 在 open_positions 写入一条 OPEN 持仓（user_id=模拟账户，不开真实币安单）
 *   - 每轮用实时价对比参考位/固定tp-sl → 触发则平仓归档到 trade_history
 * 完全复用实盘表结构，但 user_id=模拟账户，绝不触碰 veiri32/真实资金。
 */
@Slf4j
@Service
public class SimTradeService {

    /** 模拟账户固定 user_id（对应 users.username=sim_g_paper，auto_trade_enabled=0） */
    public static final long SIM_USER_ID = 3L;
    /** 模拟账户 G 策略固定杠杆 */
    private static final int SIM_LEVERAGE = 10;
    /** 模拟账户单仓保证金(U) */
    private static final BigDecimal SIM_OPEN_MARGIN = new BigDecimal("5");
    /** 每轮最多并行持仓数 */
    private static final int SIM_MAX_POSITIONS = 10;

    @Autowired
    private StrategyDetectorService strategyDetector;
    @Autowired
    private OpenPositionMapper openPositionMapper;
    @Autowired
    private TradeHistoryMapper tradeHistoryMapper;

    /**
     * 每轮真实行情扫描后调用：先平仓再开仓，模拟 G 策略全流程。
     *
     * @param tickers 本轮真实全市场行情（与实盘同一份）
     * @param gParams 模拟账户的 G 策略参数（含 tp_ratio/sl_ratio/ema/vol 等）
     */
    public void simulate(Map<String, Map<String, Object>> tickers, Map<String, Object> gParams) {
        try {
            log.info("[SimG] simulate: 行情{}币, G参数={}", tickers.size(), gParams == null ? "NULL" : gParams.keySet());
        // 诊断：打印一个样本 ticker 的字段，确认 quoteVolume/lastPrice 字段名与类型
        if (!tickers.isEmpty()) {
            java.util.Map.Entry<String, Map<String, Object>> sample = tickers.entrySet().iterator().next();
            Map<String, Object> sv = sample.getValue();
            try {
                Object qv = sv.get("quoteVolume");
                Object lp = sv.get("lastPrice");
                log.info("[SimG] 样本ticker {}: keys={}, quoteVolume={}(type={}), lastPrice={}(type={})",
                        sample.getKey(), sv.keySet(), qv, qv==null?"null":qv.getClass().getSimpleName(),
                        lp, lp==null?"null":lp.getClass().getSimpleName());
            } catch (Exception ignore) {}
        }
            // 0. 确保参考位/EMA上下文刷新（BTC趋势用同一轮）
            strategyDetector.refreshBtcTrend();

            // 1. 先平仓（用实时价对比参考位 / 固定 tp-sl）
            closeStep(tickers);

            // 2. 再开仓（检测 G 信号，模拟建仓）
            openStep(tickers, gParams);
        } catch (Exception e) {
            log.warn("[SimG] 模拟盘扫描异常: {}", e.getMessage());
        }
    }

    /** 平仓步骤：遍历模拟账户 OPEN 持仓，用实时价判断是否触发平仓 */
    private void closeStep(Map<String, Map<String, Object>> tickers) {
        List<OpenPosition> open = listOpen();
        for (OpenPosition pos : open) {
            try {
                Map<String, Object> tick = tickers.get(normSymbolKey(pos.getSymbol()));
                if (tick == null) continue;
                double cur = getDouble(tick, "lastPrice");
                LocalDateTime now = LocalDateTime.now();
                boolean isLong = "LONG".equalsIgnoreCase(pos.getDirection());
                double entry = pos.getEntryPrice().doubleValue();
                double pnlRatioPct = isLong
                        ? (cur - entry) / entry * 100.0
                        : (entry - cur) / entry * 100.0;

                String reason = null;
                double exitPrice = cur;

                // 优先动态参考位（defense=止损 / target=止盈）
                if (pos.getDefensePrice() != null && pos.getTargetPrice() != null) {
                    double def = pos.getDefensePrice().doubleValue();
                    double tgt = pos.getTargetPrice().doubleValue();
                    if (isLong) {
                        if (cur <= def) reason = "sl(动态防守位)";
                        else if (cur >= tgt) reason = "tp(动态目标位)";
                    } else {
                        if (cur >= def) reason = "sl(动态防守位)";
                        else if (cur <= tgt) reason = "tp(动态目标位)";
                    }
                }

                // 退回固定 tp/sl
                if (reason == null && pos.getTpRatio() != null && pos.getSlRatio() != null) {
                    double tp = pos.getTpRatio().doubleValue();
                    double sl = pos.getSlRatio().doubleValue();
                    if (isLong) {
                        if (pnlRatioPct >= tp) reason = "tp";
                        else if (pnlRatioPct <= sl) reason = "sl";
                    } else {
                        if (pnlRatioPct >= tp) reason = "tp";
                        else if (pnlRatioPct <= sl) reason = "sl";
                    }
                }

                if (reason != null) {
                    closePosition(pos, exitPrice, reason, cur, pnlRatioPct);
                }
            } catch (Exception e) {
                log.warn("[SimG] 平仓检测异常 {}: {}", pos.getSymbol(), e.getMessage());
            }
        }
    }

    /** 开仓步骤：检测 G 信号，未满仓则模拟建仓 */
    private void openStep(Map<String, Map<String, Object>> tickers, Map<String, Object> gParams) {
        int current = countOpen();
        if (current >= SIM_MAX_POSITIONS) return;

        log.info("[SimG] 开仓检测: 候选{}币, 当前持仓{} (gParams={})", tickers.size(), current,
                gParams == null ? "NULL" : gParams.keySet());

        // 逐币检测 G 信号，按 priority 排序择优
        List<Map<String, Object>> signals = new ArrayList<>();
        int passCount = 0, signalCount = 0;
        for (Map.Entry<String, Map<String, Object>> e : tickers.entrySet()) {
            String sym = e.getKey();
            Map<String, Object> tick = e.getValue();
            // 与实盘候选一致：G 只需有一定趋势/波动，量能达标
            if (getDouble(tick, "quoteVolume") < 1e7) continue;
            passCount++;
            try {
                // 用完整 symbol（BTCUSDT 等）检测，checkG 内部会拉 1h K线
                Map<String, Object> sig = strategyDetector.check("G", sym, tick, gParams);
                if (sig != null && alreadyHave(sym) == false) {
                    sig.put("symbol", sym);
                    sig.put("current_price", getDouble(tick, "lastPrice"));
                    double pr = getDouble(tick, "priceChangePercent") / 100.0;
                    sig.put("priority", pr);
                    signals.add(sig);
                    signalCount++;
                }
            } catch (Exception ex) {
                // skip
            }
        }
        log.info("[SimG] 开仓检测结果: 量能达标{}币, 命中G信号{}个", passCount, signalCount);
        if (signals.isEmpty()) return;

        // 按 priority 降序取前 N 个
        signals.sort((a, b) -> Double.compare(
                a.get("priority") instanceof Number ? ((Number) a.get("priority")).doubleValue() : 0,
                b.get("priority") instanceof Number ? ((Number) b.get("priority")).doubleValue() : 0));
        for (Map<String, Object> sig : signals) {
            if (current >= SIM_MAX_POSITIONS) break;
            openPosition(sig);
            current++;
        }
    }

    /** 模拟建仓（写 open_positions，不调币安下单） */
    private void openPosition(Map<String, Object> sig) {
        String sym = (String) sig.get("symbol");
        String dir = (String) sig.get("direction");
        double cur = sig.get("current_price") instanceof Number
                ? ((Number) sig.get("current_price")).doubleValue() : 0;
        if (cur <= 0) return;

        OpenPosition op = new OpenPosition();
        op.setUserId(SIM_USER_ID);
        op.setSymbol(sym);
        op.setStrategy("G");
        op.setDirection(dir);
        // qty = 保证金 * 杠杆 / 价格
        op.setQty(SIM_OPEN_MARGIN.multiply(BigDecimal.valueOf(SIM_LEVERAGE))
                .divide(BigDecimal.valueOf(cur), 8, RoundingMode.DOWN));
        op.setEntryPrice(BigDecimal.valueOf(cur));
        op.setLeverage(SIM_LEVERAGE);
        // 模拟账户固定 tp/sl（与 G 默认一致：tp+10, sl-5）
        op.setTpRatio(new BigDecimal("10"));
        op.setSlRatio(new BigDecimal("-5"));
        op.setStatus("OPEN");
        op.setRiskState("NONE");
        op.setOpenedAt(LocalDateTime.now());

        // G 策略动态参考位（从检测信号带出）
        op.setDefensePrice(optDec(sig, "defense_price"));
        op.setTargetPrice(optDec(sig, "target_price"));
        op.setProtectPrice(optDec(sig, "protect_price"));
        op.setReducePrice(optDec(sig, "reduce_price"));

        openPositionMapper.insert(op);
        log.info("[SimG] 模拟开仓 {} {} @{} 参考位(防{}/目{})",
                dir, sym, round4(cur),
                op.getDefensePrice() == null ? "-" : op.getDefensePrice().toPlainString(),
                op.getTargetPrice() == null ? "-" : op.getTargetPrice().toPlainString());
    }

    /** 平仓归档（写 trade_history：user_id=模拟账户） */
    private void closePosition(OpenPosition pos, double exitPrice, String reason,
                               double cur, double pnlRatioPct) {
        double entry = pos.getEntryPrice().doubleValue();
        // qty * (exit - entry) * 方向符号 → U
        boolean isLong = "LONG".equalsIgnoreCase(pos.getDirection());
        double pnl = pos.getQty().doubleValue()
                * (isLong ? (exitPrice - entry) : (entry - exitPrice));
        // 注意：合约 pnl 实际为 qty*(exit-entry)/exit 视角，此处按传统(exit-entry)*qty 近似，
        // 百分比用 pnlRatioPct（以入场价为基准）与实盘口径一致便于对比。

        TradeHistory th = new TradeHistory();
        th.setUserId(SIM_USER_ID);
        th.setPositionId(pos.getId());
        th.setSymbol(pos.getSymbol());
        th.setStrategy("G");
        th.setDirection(pos.getDirection());
        th.setQty(pos.getQty());
        th.setEntryPrice(pos.getEntryPrice());
        th.setExitPrice(BigDecimal.valueOf(round4(exitPrice)));
        th.setLeverage(pos.getLeverage());
        th.setPnl(BigDecimal.valueOf(round4(pnl)));
        th.setPnlRatio(BigDecimal.valueOf(round4(pnlRatioPct)));
        th.setCloseReason(reason);
        th.setOpenedAt(pos.getOpenedAt());
        th.setClosedAt(LocalDateTime.now());
        tradeHistoryMapper.insert(th);

        // 更新持仓为已平仓
        pos.setStatus("CLOSED");
        pos.setClosedAt(LocalDateTime.now());
        pos.setClosePrice(BigDecimal.valueOf(round4(exitPrice)));
        pos.setCloseReason(reason);
        pos.setPnl(BigDecimal.valueOf(round4(pnl)));
        pos.setPnlRatio(BigDecimal.valueOf(round4(pnlRatioPct)));
        openPositionMapper.updateById(pos);

        log.info("[SimG] 模拟平仓 {} {} @{} 原因={} 盈亏={}U ({:.1f}%)",
                pos.getDirection(), pos.getSymbol(), round4(exitPrice), reason, round4(pnl), pnlRatioPct);
    }

    private List<OpenPosition> listOpen() {
        return openPositionMapper.selectList(new LambdaQueryWrapper<OpenPosition>()
                .eq(OpenPosition::getUserId, SIM_USER_ID)
                .eq(OpenPosition::getStatus, "OPEN"));
    }

    private int countOpen() {
        return Math.toIntExact(openPositionMapper.selectCount(new LambdaQueryWrapper<OpenPosition>()
                .eq(OpenPosition::getUserId, SIM_USER_ID)
                .eq(OpenPosition::getStatus, "OPEN")));
    }

    private boolean alreadyHave(String sym) {
        return openPositionMapper.selectCount(new LambdaQueryWrapper<OpenPosition>()
                .eq(OpenPosition::getUserId, SIM_USER_ID)
                .eq(OpenPosition::getSymbol, sym)
                .eq(OpenPosition::getStatus, "OPEN")) > 0;
    }

    private static String normSymbolKey(String sym) {
        // tickers 的 key 与持仓 symbol 都是币安原始交易对（如 BTCUSDT），直接原样匹配
        return sym;
    }

    private static double getDouble(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof CharSequence) {
            try { return Double.parseDouble(v.toString().trim()); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }

    private static BigDecimal optDec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
