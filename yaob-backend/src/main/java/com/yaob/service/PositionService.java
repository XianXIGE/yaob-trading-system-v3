package com.yaob.service;

import com.yaob.entity.OpenPosition;
import com.yaob.entity.TradeHistory;
import com.yaob.entity.StrategyStat;
import com.yaob.mapper.OpenPositionMapper;
import com.yaob.mapper.TradeHistoryMapper;
import com.yaob.mapper.StrategyStatMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class PositionService {

    @Autowired
    private OpenPositionMapper openPositionMapper;
    @Autowired
    private TradeHistoryMapper tradeHistoryMapper;
    @Autowired
    private StrategyStatMapper strategyStatMapper;
    @Autowired
    private BinanceFapiService fapi;
    @Autowired
    private TradeEngineService tradeEngine;

    public List<OpenPosition> getOpenPositions(Long userId) {
        return openPositionMapper.findOpenByUserId(userId);
    }

    public void closePosition(Long userId, Long positionId) {
        OpenPosition pos = openPositionMapper.selectById(positionId);
        if (pos == null || !pos.getUserId().equals(userId)) {
            throw new RuntimeException("持仓不存在");
        }
        if (!"OPEN".equals(pos.getStatus())) {
            throw new RuntimeException("该持仓已平仓");
        }

        // 调用币安 API 平仓
        String side = "LONG".equals(pos.getDirection()) ? "SELL" : "BUY";
        double qty = pos.getQty().doubleValue();
        try {
            fapi.closePosition(pos.getSymbol(), qty, side);
        } catch (Exception e) {
            throw new RuntimeException("平仓失败: " + e.getMessage());
        }

        // 更新记录
        pos.setStatus("CLOSED");
        pos.setClosedAt(LocalDateTime.now());
        pos.setCloseReason("manual");

        // 归档到 trade_history
        TradeHistory th = new TradeHistory();
        th.setUserId(userId);
        th.setPositionId(pos.getId());
        th.setSymbol(pos.getSymbol());
        th.setStrategy(pos.getStrategy());
        th.setDirection(pos.getDirection());
        th.setQty(pos.getQty());
        th.setEntryPrice(pos.getEntryPrice());
        th.setLeverage(pos.getLeverage());
        th.setCloseReason("manual");
        th.setOpenedAt(pos.getOpenedAt());
        th.setClosedAt(LocalDateTime.now());
        tradeHistoryMapper.insert(th);

        openPositionMapper.updateById(pos);
    }

    public List<TradeHistory> getTradeHistory(Long userId, int limit) {
        return tradeHistoryMapper.findByUserIdOrderByOpenedAtDesc(userId, limit);
    }

    public List<TradeHistory> getAllTradeHistory(Long userId) {
        return tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId);
    }

    public List<StrategyStat> getStrategyStats(Long userId) {
        return strategyStatMapper.findByUserId(userId);
    }

    public Map<String, Object> getTradeProfitStats(Long userId) {
        List<TradeHistory> all = tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId);

        // 按天分布
        Map<String, Map<String, Object>> daily = new LinkedHashMap<>();
        for (TradeHistory h : all) {
            if (h.getCloseReason() == null) continue;
            LocalDateTime closeTime = h.getClosedAt() != null ? h.getClosedAt() : h.getOpenedAt();
            if (closeTime == null) continue;
            String day = closeTime.toLocalDate().toString();
            Map<String, Object> d = daily.computeIfAbsent(day, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pnl", 0.0);
                m.put("trades", 0);
                m.put("wins", 0);
                return m;
            });
            d.put("trades", (int) d.get("trades") + 1);
            if (h.getPnlRatio() != null) {
                double pnl = (double) d.get("pnl") + h.getPnlRatio().doubleValue();
                d.put("pnl", pnl);
                if (h.getPnlRatio().compareTo(BigDecimal.ZERO) > 0) {
                    d.put("wins", (int) d.get("wins") + 1);
                }
            }
        }

        List<Map<String, Object>> dailyList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : daily.entrySet()) {
            Map<String, Object> d = e.getValue();
            int trades = (int) d.get("trades");
            int wins = (int) d.get("wins");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("day", e.getKey());
            out.put("pnl", Math.round((double) d.get("pnl") * 100.0) / 100.0);
            out.put("trades", trades);
            out.put("win_rate", trades > 0 ? Math.round(wins * 1000.0 / trades) / 10.0 : 0);
            dailyList.add(out);
        }
        dailyList.sort((a, b) -> b.get("day").toString().compareTo(a.get("day").toString()));

        // 按标的分布
        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        for (TradeHistory h : all) {
            if (h.getCloseReason() == null) continue;
            Map<String, Object> b = bySymbol.computeIfAbsent(h.getSymbol(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("trades", 0);
                m.put("pnl", 0.0);
                m.put("wins", 0);
                return m;
            });
            b.put("trades", (int) b.get("trades") + 1);
            if (h.getPnlRatio() != null) {
                double pnl = (double) b.get("pnl") + h.getPnlRatio().doubleValue();
                b.put("pnl", pnl);
                if (h.getPnlRatio().compareTo(BigDecimal.ZERO) > 0) {
                    b.put("wins", (int) b.get("wins") + 1);
                }
            }
        }

        List<Map<String, Object>> symbolList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : bySymbol.entrySet()) {
            Map<String, Object> b = e.getValue();
            int trades = (int) b.get("trades");
            int wins = (int) b.get("wins");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", e.getKey());
            out.put("trades", trades);
            out.put("pnl", Math.round((double) b.get("pnl") * 100.0) / 100.0);
            out.put("win_rate", trades > 0 ? Math.round(wins * 1000.0 / trades) / 10.0 : 0);
            symbolList.add(out);
        }
        symbolList.sort((a, b) -> Double.compare((double) b.get("pnl"), (double) a.get("pnl")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("daily", dailyList);
        result.put("by_symbol", symbolList);
        return result;
    }
}
