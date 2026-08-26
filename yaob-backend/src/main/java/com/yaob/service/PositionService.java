package com.yaob.service;

import com.yaob.entity.OpenPosition;
import com.yaob.entity.TradeHistory;
import com.yaob.entity.StrategyStat;
import com.yaob.entity.User;
import com.yaob.mapper.OpenPositionMapper;
import com.yaob.mapper.TradeHistoryMapper;
import com.yaob.mapper.StrategyStatMapper;
import com.yaob.mapper.UserMapper;
import com.yaob.config.CryptoUtil;
import com.fasterxml.jackson.databind.JsonNode;
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
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    public List<OpenPosition> getOpenPositions(Long userId) {
        return openPositionMapper.findOpenByUserId(userId);
    }

    /**
     * 按 symbol 平仓：优先从币安实时账户读取真实持仓直接下平仓单，
     * 不再依赖数据库 open_positions 记录（实盘可能有手动/其他来源的仓库中查不到）。
     * symbol 可能是 "ONG/USDT"(前端normSymbol)或 "ONGUSDT"，统一去掉斜杠对齐币安格式。
     */
    public void closePositionBySymbol(Long userId, String symbol) {
        String sym = symbol == null ? "" : symbol.replace("/", "").trim().toUpperCase();
        if (sym.isBlank()) {
            throw new RuntimeException("缺少交易对参数");
        }

        User user = userMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");

        String apiKey = null;
        String apiSecret = null;
        if (user.getBinanceApiKey() != null && !user.getBinanceApiKey().isBlank()) {
            try {
                apiKey = cryptoUtil.decrypt(user.getBinanceApiKey());
                apiSecret = cryptoUtil.decrypt(user.getBinanceApiSecret());
            } catch (Exception e) {
                throw new RuntimeException("API密钥解密失败: " + e.getMessage());
            }
        }
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new RuntimeException("未配置有效的币安API密钥");
        }

        boolean hedge = "hedge".equalsIgnoreCase(user.getPositionMode());
        double realClosePrice = 0;

        try {
            // 从币安实时账户读取真实持仓
            JsonNode acct = fapi.account(apiKey, apiSecret);
            JsonNode positionsNode = acct != null ? acct.get("positions") : null;
            double amt = 0;
            String positionSide = null;
            if (positionsNode != null && positionsNode.isArray()) {
                for (JsonNode p : positionsNode) {
                    String psym = p.get("symbol").asText();
                    if (!sym.equals(psym)) continue;
                    double a = p.get("positionAmt").asDouble();
                    if (a == 0) continue;
                    // 累加同 symbol 的双向仓位；Hedge 下 positionSide 区分方向
                    amt += a;
                    positionSide = p.has("positionSide") && !p.get("positionSide").isNull()
                            ? p.get("positionSide").asText() : null;
                }
            }
            if (amt == 0) {
                throw new RuntimeException("币安账户中无 " + sym + " 持仓");
            }

            // 方向：amt>0 做多(平仓SELL)，amt<0 做空(平仓BUY)
            String side = amt > 0 ? "SELL" : "BUY";
            double qty = Math.abs(amt);
            String ps = hedge
                    ? (amt > 0 ? "LONG" : "SHORT")
                    : "BOTH";

            log.info("[close_position] userId={} symbol={} 真实持仓 qty={} side={} positionSide={}",
                    userId, sym, qty, side, ps);
            JsonNode orderResult = fapi.closePosition(sym, qty, side, ps, apiKey, apiSecret);
            // 优先用下单返回的真实成交均价 avgPrice 作为平仓价
            if (orderResult != null) {
                if (orderResult.has("avgPrice") && !orderResult.get("avgPrice").isNull()) {
                    realClosePrice = Double.parseDouble(orderResult.get("avgPrice").asText());
                } else if (orderResult.has("price") && !orderResult.get("price").isNull()) {
                    realClosePrice = Double.parseDouble(orderResult.get("price").asText());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("平仓失败: " + e.getMessage());
        }

        // 平仓价：优先用下单真实成交价，兜底用实时行情 lastPrice
        double closePrice = realClosePrice;
        if (closePrice <= 0) {
            try {
                for (Map<String, Object> t : fapi.allTickers()) {
                    Object tsym = t.get("symbol");
                    if (tsym != null && sym.equals(tsym.toString())) {
                        Object lp = t.get("lastPrice");
                        if (lp != null) closePrice = Double.parseDouble(lp.toString());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[close_position] 获取平仓价失败: {}", e.getMessage());
            }
        }

        // 清理/归档数据库里对应记录（若存在，多向持仓遍历清理），并补算 pnl
        List<OpenPosition> list = openPositionMapper.findOpenByUserId(userId);
        for (OpenPosition pos : list) {
            if (pos.getSymbol().equalsIgnoreCase(sym)) {
                double entry = pos.getEntryPrice() != null ? pos.getEntryPrice().doubleValue() : 0;
                String dir = pos.getDirection();
                double qtyD = pos.getQty() != null ? pos.getQty().doubleValue() : 0;
                // 盈亏: SHORT(做空)平仓为买入, 跌了赚(entry-close); LONG(做多)涨了赚(close-entry)
                double pnl = (closePrice > 0 && entry > 0)
                        ? ("SHORT".equalsIgnoreCase(dir)
                            ? (entry - closePrice) * qtyD
                            : (closePrice - entry) * qtyD)
                        : 0;
                double margin = qtyD * entry / (pos.getLeverage() != null ? pos.getLeverage() : 1);
                double pnlRatio = (margin > 0) ? pnl / margin * 100 : 0;

                pos.setStatus("CLOSED");
                pos.setClosedAt(LocalDateTime.now());
                pos.setClosePrice(closePrice > 0 ? BigDecimal.valueOf(closePrice) : null);
                pos.setCloseReason("manual");
                pos.setPnl(BigDecimal.valueOf(pnl));
                pos.setPnlRatio(BigDecimal.valueOf(pnlRatio));
                openPositionMapper.updateById(pos);

                TradeHistory th = new TradeHistory();
                th.setUserId(userId);
                th.setPositionId(pos.getId());
                th.setSymbol(pos.getSymbol());
                th.setStrategy(pos.getStrategy());
                th.setDirection(pos.getDirection());
                th.setQty(pos.getQty());
                th.setEntryPrice(pos.getEntryPrice());
                th.setExitPrice(closePrice > 0 ? BigDecimal.valueOf(closePrice) : null);
                th.setLeverage(pos.getLeverage());
                th.setPnl(BigDecimal.valueOf(pnl));
                th.setPnlRatio(BigDecimal.valueOf(pnlRatio));
                th.setCloseReason("manual");
                th.setOpenedAt(pos.getOpenedAt());
                th.setClosedAt(LocalDateTime.now());
                tradeHistoryMapper.insert(th);

                // 更新策略统计（胜率对比图表数据源）
                tradeEngine.updateStrategyStats(userId, pos.getStrategy(), "manual", BigDecimal.valueOf(pnl));
            }
        }
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
        // 双向持仓模式(Hedge)平仓必须带对应 positionSide; 单向传 BOTH
        User user = userMapper.selectById(userId);
        boolean hedge = user != null && "hedge".equalsIgnoreCase(user.getPositionMode());
        String positionSide = hedge ? ("LONG".equals(pos.getDirection()) ? "LONG" : "SHORT") : "BOTH";
        double qty = pos.getQty().doubleValue();

        // 解密用户币安 API 密钥
        String apiKey = null;
        String apiSecret = null;
        if (user != null && user.getBinanceApiKey() != null && !user.getBinanceApiKey().isBlank()) {
            try {
                apiKey = cryptoUtil.decrypt(user.getBinanceApiKey());
                apiSecret = cryptoUtil.decrypt(user.getBinanceApiSecret());
            } catch (Exception e) {
                throw new RuntimeException("API密钥解密失败: " + e.getMessage());
            }
        }
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new RuntimeException("未配置有效的币安API密钥");
        }

        try {
            fapi.closePosition(pos.getSymbol(), qty, side, positionSide, apiKey, apiSecret);
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
            if (h.getPnl() != null) {
                // [v3.5 修复] 正确用 USDT 金额 pnl 累加(而非 pnlRatio 百分比)，与历史列表/缓存表口径统一
                double pnl = (double) d.get("pnl") + h.getPnl().doubleValue();
                d.put("pnl", pnl);
                if (h.getPnl().compareTo(BigDecimal.ZERO) > 0) {
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
            if (h.getPnl() != null) {
                // [v3.5 修复] pnlRatio 百分比 -> pnl 金额
                double pnl = (double) b.get("pnl") + h.getPnl().doubleValue();
                b.put("pnl", pnl);
                if (h.getPnl().compareTo(BigDecimal.ZERO) > 0) {
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
