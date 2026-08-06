package com.yaob.service;

import com.yaob.entity.StrategyStat;
import com.yaob.entity.TradeHistory;
import com.yaob.mapper.StrategyStatMapper;
import com.yaob.mapper.TradeHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
public class StatsService {

    @Autowired
    private StrategyStatMapper strategyStatMapper;
    @Autowired
    private TradeHistoryMapper tradeHistoryMapper;

    private static final Map<String, String> STAT = new LinkedHashMap<>();
    static {
        STAT.put("A", "空");
        STAT.put("B", "空");
        STAT.put("C", "多");
        STAT.put("D", "空");
        STAT.put("E", "多");
        STAT.put("F", "斐波那契双向");
    }

    public Map<String, Object> getStats(Long userId) {
        List<StrategyStat> stats = strategyStatMapper.findByUserId(userId);
        int totalTrades = 0;
        int winTrades = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (StrategyStat s : stats) {
            totalTrades += s.getTotalTrades();
            winTrades += s.getWinTrades();
            totalPnl = totalPnl.add(s.getTotalPnl() != null ? s.getTotalPnl() : BigDecimal.ZERO);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_trades", totalTrades);
        result.put("win_trades", winTrades);
        result.put("win_rate", totalTrades > 0 ? Math.round(winTrades * 1000.0 / totalTrades) / 10.0 : 0);
        result.put("total_pnl", totalPnl);
        return result;
    }

    public void resetStats(Long userId) {
        List<StrategyStat> stats = strategyStatMapper.findByUserId(userId);
        for (StrategyStat s : stats) {
            s.setTotalTrades(0);
            s.setWinTrades(0);
            s.setTotalPnl(BigDecimal.ZERO);
            s.setTpCount(0);
            s.setSlCount(0);
            s.setManualCount(0);
            strategyStatMapper.updateById(s);
        }
    }

    public List<Map<String, Object>> getStrategyStats(Long userId) {
        List<TradeHistory> all = tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId);
        Map<String, Map<String, Object>> stratMap = new LinkedHashMap<>();
        for (TradeHistory h : all) {
            if (h.getCloseReason() == null) continue;
            String s = h.getStrategy() != null ? h.getStrategy().toUpperCase() : "A";
            Map<String, Object> st = stratMap.computeIfAbsent(s, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("trades", 0);
                m.put("wins", 0);
                m.put("pnl_sum", 0.0);
                m.put("tp", 0);
                m.put("sl", 0);
                m.put("manual", 0);
                return m;
            });
            st.put("trades", (int) st.get("trades") + 1);
            if (h.getPnlRatio() != null) {
                st.put("pnl_sum", (double) st.get("pnl_sum") + h.getPnlRatio().doubleValue());
                if (h.getPnlRatio().compareTo(BigDecimal.ZERO) > 0) {
                    st.put("wins", (int) st.get("wins") + 1);
                }
                if ("tp".equals(h.getCloseReason())) st.put("tp", (int) st.get("tp") + 1);
                else if ("sl".equals(h.getCloseReason())) st.put("sl", (int) st.get("sl") + 1);
                else st.put("manual", (int) st.get("manual") + 1);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : stratMap.entrySet()) {
            Map<String, Object> st = e.getValue();
            int trades = (int) st.get("trades");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", e.getKey());
            row.put("type", STAT.getOrDefault(e.getKey(), e.getKey()));
            row.put("trades", trades);
            row.put("win_rate", trades > 0 ? Math.round((int) st.get("wins") * 1000.0 / trades) / 10.0 : 0);
            row.put("pnl_sum", Math.round((double) st.get("pnl_sum") * 100.0) / 100.0);
            row.put("tp", st.get("tp"));
            row.put("sl", st.get("sl"));
            row.put("manual", st.get("manual"));
            out.add(row);
        }
        out.sort((a, b) -> Integer.compare((int) b.get("trades"), (int) a.get("trades")));
        return out;
    }
}
