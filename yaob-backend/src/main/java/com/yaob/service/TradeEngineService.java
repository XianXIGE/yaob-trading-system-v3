package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaob.entity.*;
import com.yaob.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TradeEngineService {

    @Autowired
    private BinanceFapiService fapi;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OpenPositionMapper openPositionMapper;
    @Autowired
    private TradeHistoryMapper tradeHistoryMapper;
    @Autowired
    private StrategyConfigMapper strategyConfigMapper;
    @Autowired
    private StrategyStatMapper strategyStatMapper;
    @Autowired
    private ExcludedSymbolMapper excludedSymbolMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private RiskManager riskManager;
    @Autowired
    private MarketDataService marketData;
    @Autowired
    private StrategyChecker strategyChecker;
    @Autowired
    private LiquidityChecker liquidityChecker;
    @Autowired
    private ObjectMapper objectMapper;

    // 运行时状态: userId -> runtime
    private final Map<Long, RuntimeState> runtimeMap = new ConcurrentHashMap<>();

    public static class RuntimeState {
        public String scannerStatus = "⏳ 倒计时";
        public double lastScanDuration = 0.0;
        public long nextScanTimestamp = System.currentTimeMillis() / 1000 + 120;
        public long scanStartTimestamp = 0;
        public BigDecimal accountTotalAssets = BigDecimal.ZERO;
        public BigDecimal availableMargin = BigDecimal.ZERO;
        public List<Map<String, Object>> candidatePool = new ArrayList<>();
        public List<Map<String, Object>> positions = new ArrayList<>();
    }

    // 策略类型描述
    private static final Map<String, String> STAT = new LinkedHashMap<>();
    static {
        STAT.put("A", "空");
        STAT.put("B", "空");
        STAT.put("C", "多");
        STAT.put("D", "空");
        STAT.put("E", "多");
        STAT.put("F", "斐波那契双向");
    }

    public RuntimeState getRuntime(Long userId) {
        return runtimeMap.computeIfAbsent(userId, k -> new RuntimeState());
    }

    // ==================== Scan Loop ====================

    /** 轻量级持仓刷新：每10秒更新账户资产和持仓（不等完整扫描） */
    public void positionRefreshLoop() {
        while (true) {
            try {
                List<User> traders = getActiveTraders();
                for (User user : traders) {
                    if (user.getBinanceApiKey() == null || user.getBinanceApiKey().isBlank()) continue;
                    try {
                        String[] keys = userService.getDecryptedApiKeys(user);
                        fapi.setApiKeys(keys[0], keys[1]);
                        if (!fapi.isDryRun()) {
                            RuntimeState rt = getRuntime(user.getId());
                            updateAccountAndPositions(rt, user);
                        }
                    } catch (Exception e) {
                        // 静默失败，不打扰主日志
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            try {
                Thread.sleep(10_000); // 10秒刷新一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void scanLoop() {
        while (true) {
            try {
                List<User> traders = getActiveTraders();
                for (User user : traders) {
                    try {
                        runScan(user);
                    } catch (Exception e) {
                        log.error("[scan:{} 扫描异常", user.getUsername(), e);
                    }
                }
            } catch (Exception e) {
                log.error("[scan] loop err", e);
            }
            try {
                Thread.sleep(120_000); // 2分钟扫描间隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private List<User> getActiveTraders() {
        // 查找所有用户：有API Key且开启自动交易的优先，
        // 但也要包含至少有一个用户能扫描行情（即使没配API Key）
        List<User> all = userMapper.selectList(null);
        List<User> traders = new ArrayList<>();
        List<User> fallback = new ArrayList<>();
        for (User u : all) {
            if (u.getBinanceApiKey() != null && !u.getBinanceApiKey().isBlank()) {
                // 有 API Key 的用户加入
                if (Boolean.TRUE.equals(u.getAutoTradeEnabled())) {
                    traders.add(0, u); // 开启自动交易的优先
                } else {
                    fallback.add(u); // 有Key但没开自动交易
                }
            } else if (fallback.isEmpty() && traders.isEmpty()) {
                fallback.add(u); // 至少留一个用户用于行情扫描
            }
        }
        // 合并：有Key的在前，没Key的在后
        traders.addAll(fallback);
        return traders;
    }

    // ==================== Run Scan ====================

    @SuppressWarnings("unchecked")
    public void runScan(User user) {
        Long userId = user.getId();
        RuntimeState rt = getRuntime(userId);
        Map<String, Map<String, Object>> params = userService.getStrategyParams(userId);
        Map<String, Boolean> states = userService.getStrategyStates(userId);

        // VIP 过期检查
        userService.checkVipExpiry(user);

        rt.scannerStatus = "正在扫描...";
        rt.scanStartTimestamp = System.currentTimeMillis() / 1000;
        long startTime = System.currentTimeMillis();

        try {
            // 设置 API keys（解密后使用，兼容历史明文）
            String[] keys = userService.getDecryptedApiKeys(user);
            fapi.setApiKeys(keys[0], keys[1]);

            // 获取 24h ticker 全市场数据
            log.info("[scan:{}] 开始获取币安行情(共享缓存)...", user.getUsername());
            Map<String, Map<String, Object>> tickers = marketData.getTickerMap();
            log.info("[scan:{}] 获取到 {} 个交易对行情", user.getUsername(), tickers.size());

            // 先更新账户资产和持仓（不用等候选池跑完）
            if (!fapi.isDryRun()) {
                updateAccountAndPositions(rt, user);
            }

            // 生成候选池
            List<Map<String, Object>> pool = candidates(tickers, user, params, states);
            rt.candidatePool = pool;

            // 自动交易
            if (Boolean.TRUE.equals(user.getAutoTradeEnabled())
                    && user.getBinanceApiKey() != null && !user.getBinanceApiKey().isBlank()
                    && !fapi.isDryRun()) {

                // 自动平仓引擎
                autoClosePositions(user, tickers, params);

                // 自动开仓
                autoOpenPositions(user, rt, pool, params, states);
            }

            rt.scannerStatus = "⏳ 倒计时";
        } catch (Exception e) {
            log.error("[scan:{}] FAPI错误: {}", user.getUsername(), e.getMessage());
            rt.scannerStatus = "行情获取失败";
        }

        rt.lastScanDuration = (System.currentTimeMillis() - startTime) / 1000.0;
        rt.nextScanTimestamp = System.currentTimeMillis() / 1000 + 120;
    }

    // ==================== Candidates Generation ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(Map<String, Map<String, Object>> tickers,
                                                  User user,
                                                  Map<String, Map<String, Object>> params,
                                                  Map<String, Boolean> states) {
        List<Map<String, Object>> pool = new ArrayList<>();

        // 获取用户黑名单
        Set<String> excluded = getExcludedSet(user);

        // 计算最低成交额门槛
        double minVol = Double.MAX_VALUE;
        for (String sk : params.keySet()) {
            if (Boolean.TRUE.equals(states.get(sk.toUpperCase()))) {
                Object vol = params.get(sk).get("vol_min");
                double v = vol instanceof Number ? ((Number) vol).doubleValue() : 1e7;
                minVol = Math.min(minVol, v);
            }
        }
        if (minVol == Double.MAX_VALUE) minVol = 0;

        int count = 0;
        for (Map.Entry<String, Map<String, Object>> entry : tickers.entrySet()) {
            if (count++ > 600) break;
            String sym = entry.getKey();
            Map<String, Object> tick = entry.getValue();

            if (!sym.endsWith("USDT")) continue;

            // 排除大盘币
            if (Boolean.TRUE.equals(user.getExcludeLargeCap()) && excluded.contains(sym)) {
                continue;
            }

            double qv = getDouble(tick, "quoteVolume");
            if (qv == 0 || qv < minVol) continue;

            // 逐策略检测
            for (String sk : new String[]{"A", "B", "C", "D", "E", "F"}) {
                if (!Boolean.TRUE.equals(states.get(sk))) continue;
                Map<String, Object> p = params.get(sk);
                if (p == null) continue;

                Map<String, Object> sig = strategyChecker.check(sk, sym, tick, p);

                if (sig != null) {
                    // 资金费率极端时跳过（默认 |rate| >= 0.3%）
                    if (marketData.isFundingExtreme(sym, 0.003)) {
                        continue;
                    }
                    sig.put("symbol", normSymbol(sym));
                    sig.put("current_price", getDouble(tick, "lastPrice"));
                    sig.put("priority", getDouble(tick, "priceChangePercent") / 100.0);
                    sig.put("trigger_time", LocalDateTime.now().toString());
                    sig.put("unopen_reason", "等待开仓");
                    pool.add(sig);
                    break; // 每币取一个信号
                }
            }
        }

        // 排序: SHORT在前, LONG在后, 组内按 priority 降序
        List<Map<String, Object>> shorts = new ArrayList<>();
        List<Map<String, Object>> longs = new ArrayList<>();
        for (Map<String, Object> c : pool) {
            if ("SHORT".equals(c.get("direction"))) {
                shorts.add(c);
            } else {
                longs.add(c);
            }
        }
        shorts.sort((a, b) -> Double.compare(getDouble(b, "priority"), getDouble(a, "priority")));
        longs.sort((a, b) -> Double.compare(getDouble(b, "priority"), getDouble(a, "priority")));

        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(shorts);
        result.addAll(longs);
        if (result.size() > 10) result = result.subList(0, 10);
        return result;
    }

    // Strategy checks 已拆分至 StrategyChecker

    // ==================== Auto Close Positions ====================

    @SuppressWarnings("unchecked")
    private void autoClosePositions(User user, Map<String, Map<String, Object>> tickers,
                                     Map<String, Map<String, Object>> params) {
        Long userId = user.getId();
        List<OpenPosition> openPositions = openPositionMapper.findOpenByUserId(userId);
        if (openPositions.isEmpty()) return;

        java.util.Set<Long> timeoutIds = new java.util.HashSet<>();
        for (OpenPosition tp : riskManager.findTimeoutPositions(userId)) {
            timeoutIds.add(tp.getId());
        }

        // 获取实时持仓
        Map<String, JsonNode> livePositions = new HashMap<>();
        try {
            JsonNode acct = fapi.account();
            JsonNode positionsNode = acct.get("positions");
            if (positionsNode != null && positionsNode.isArray()) {
                for (JsonNode p : positionsNode) {
                    double amt = p.get("positionAmt").asDouble();
                    if (amt != 0) {
                        livePositions.put(p.get("symbol").asText(), p);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[auto-close:{}] 获取持仓失败: {}", user.getUsername(), e.getMessage());
        }

        for (OpenPosition pos : openPositions) {
            String sym0 = pos.getSymbol();
            JsonNode livePos = livePositions.get(sym0);

            if (livePos == null) {
                // 该币已无持仓（手动平/已平/强平），清理为 manual
                closePositionRecord(pos, null, "manual", null, null, userId);
                continue;
            }

            double entry = pos.getEntryPrice().doubleValue();
            double last = 0;
            // 尝试从 ticker 获取最新价
            Map<String, Object> tick = tickers.get(sym0);
            if (tick != null) {
                last = getDouble(tick, "lastPrice");
            }
            if (last == 0) {
                last = livePos.get("entryPrice").asDouble();
            }

            double amt = livePos.get("positionAmt").asDouble();
            double mg = livePos.get("initialMargin").asDouble();
            double pnl = livePos.get("unrealizedProfit").asDouble();

            // 盈亏比例用保证金口径: pnl / initialMargin * 100
            double ratio;
            if (mg != 0) {
                ratio = pnl / mg * 100;
            } else if (entry <= 0) {
                ratio = 0.0;
            } else if (amt > 0) {
                ratio = (last - entry) / entry * 100;
            } else {
                ratio = (entry - last) / entry * 100;
            }

            double tp = pos.getTpRatio() != null ? pos.getTpRatio().doubleValue() : 0;
            double sl = pos.getSlRatio() != null ? pos.getSlRatio().doubleValue() : 0;

            // 修复: tp=0 或 sl=0 时从策略参数现取 (修复大小写不匹配 bug)
            if ((tp == 0 || sl == 0) && pos.getStrategy() != null) {
                String sk = pos.getStrategy().toLowerCase();
                Map<String, Object> sp = params.get(sk);
                if (sp != null) {
                    if (tp == 0) {
                        Object tpObj = sp.get("tp_ratio");
                        if (tpObj instanceof Number) tp = ((Number) tpObj).doubleValue();
                    }
                    if (sl == 0) {
                        Object slObj = sp.get("sl_ratio");
                        if (slObj instanceof Number) sl = ((Number) slObj).doubleValue();
                    }
                }
            }

            double qty = Math.abs(amt);
            if (qty <= 0) continue;

            String side = amt > 0 ? "SELL" : "BUY";

            // 触发超时 / 止盈 / 止损
            boolean shouldClose = false;
            String reason = null;
            if (timeoutIds.contains(pos.getId())) {
                log.info("[auto-close:{}] {} 持仓超时强制平仓", user.getUsername(), sym0);
                shouldClose = true;
                reason = "timeout";
            } else if (tp > 0 && ratio >= tp) {
                log.info("[auto-close:{}] {} 止盈 ratio={}% >= tp={}", user.getUsername(), sym0, String.format("%.2f", ratio), tp);
                shouldClose = true;
                reason = "tp";
            } else if (sl < 0 && ratio <= sl) {
                log.info("[auto-close:{}] {} 止损 ratio={}% <= sl={}", user.getUsername(), sym0, String.format("%.2f", ratio), sl);
                shouldClose = true;
                reason = "sl";
            }

            if (shouldClose) {
                try {
                    fapi.closePosition(sym0, qty, side);
                    log.info("[auto-close:{}] {} 已平 {} ({})", user.getUsername(), sym0, qty, side);
                    closePositionRecord(pos, BigDecimal.valueOf(last), reason,
                            BigDecimal.valueOf(pnl), BigDecimal.valueOf(ratio), userId);
                } catch (Exception e) {
                    log.error("[auto-close:{}] {} 平仓失败: {}", user.getUsername(), sym0, e.getMessage());
                }
            }
        }
    }

    private void closePositionRecord(OpenPosition pos, BigDecimal closePrice, String reason,
                                      BigDecimal pnl, BigDecimal pnlRatio, Long userId) {
        pos.setStatus("CLOSED");
        pos.setClosedAt(LocalDateTime.now());
        pos.setClosePrice(closePrice);
        pos.setCloseReason(reason);
        pos.setPnl(pnl);
        pos.setPnlRatio(pnlRatio);
        openPositionMapper.updateById(pos);

        // 归档到 trade_history
        TradeHistory th = new TradeHistory();
        th.setUserId(userId);
        th.setPositionId(pos.getId());
        th.setSymbol(pos.getSymbol());
        th.setStrategy(pos.getStrategy());
        th.setDirection(pos.getDirection());
        th.setQty(pos.getQty());
        th.setEntryPrice(pos.getEntryPrice());
        th.setExitPrice(closePrice);
        th.setLeverage(pos.getLeverage());
        th.setPnl(pnl);
        th.setPnlRatio(pnlRatio);
        th.setCloseReason(reason);
        th.setOpenedAt(pos.getOpenedAt());
        th.setClosedAt(LocalDateTime.now());
        tradeHistoryMapper.insert(th);

        // 更新策略统计 + 日盈亏（用于熔断）
        updateStrategyStats(userId, pos.getStrategy(), reason, pnl);
        if (pnl != null) {
            riskManager.recordRealizedPnl(userId, pnl);
        }
    }

    private void updateStrategyStats(Long userId, String strategy, String reason, BigDecimal pnl) {
        StrategyStat stat = strategyStatMapper.findByUserIdAndStrategy(userId, strategy);
        if (stat == null) {
            stat = new StrategyStat();
            stat.setUserId(userId);
            stat.setStrategy(strategy);
            stat.setTotalTrades(0);
            stat.setWinTrades(0);
            stat.setTotalPnl(BigDecimal.ZERO);
            stat.setTpCount(0);
            stat.setSlCount(0);
            stat.setManualCount(0);
            strategyStatMapper.insert(stat);
        }
        stat.setTotalTrades(stat.getTotalTrades() + 1);
        if (pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0) {
            stat.setWinTrades(stat.getWinTrades() + 1);
        }
        if (pnl != null) {
            stat.setTotalPnl(stat.getTotalPnl().add(pnl));
        }
        if ("tp".equals(reason)) stat.setTpCount(stat.getTpCount() + 1);
        else if ("sl".equals(reason)) stat.setSlCount(stat.getSlCount() + 1);
        else stat.setManualCount(stat.getManualCount() + 1);
        strategyStatMapper.updateById(stat);
    }

    // ==================== Auto Open Positions ====================

    @SuppressWarnings("unchecked")
    private void autoOpenPositions(User user, RuntimeState rt,
                                    List<Map<String, Object>> pool,
                                    Map<String, Map<String, Object>> params,
                                    Map<String, Boolean> states) {
        Long userId = user.getId();

        // 当前持仓币种集合
        Set<String> held = new HashSet<>();
        try {
            JsonNode acct = fapi.account();
            JsonNode positionsNode = acct.get("positions");
            if (positionsNode != null && positionsNode.isArray()) {
                for (JsonNode p : positionsNode) {
                    if (p.get("positionAmt").asDouble() != 0) {
                        held.add(p.get("symbol").asText());
                    }
                }
            }
        } catch (Exception e) {
            // 拿不到持仓不阻断
        }

        double avail = rt.availableMargin != null ? rt.availableMargin.doubleValue() : 0;
        double openMargin = user.getOpenMargin() != null ? user.getOpenMargin().doubleValue() : 5.0;
        int leverage = user.getLeverage() != null ? user.getLeverage() : 5;

        List<String> opened = new ArrayList<>();
        int maxOpen = Math.min(3, pool.size());
        for (int i = 0; i < maxOpen; i++) {
            Map<String, Object> cand = pool.get(i);
            String sym0 = cand.get("symbol").toString().replace("/", "").toUpperCase();

            if (held.contains(sym0)) continue;

            // 保证金门槛
            if (avail < openMargin) {
                cand.put("unopen_reason", String.format("保证金不足(%.2f<%.2f)", avail, openMargin));
                continue;
            }

            // ===== 统一风控检查 =====
            RiskManager.RiskDecision decision = riskManager.canOpen(
                    user, avail, held.size() + opened.size(), openMargin, leverage);
            if (!decision.isAllowed()) {
                cand.put("unopen_reason", "风控拒绝: " + decision.getReason());
                log.info("[auto-open:{}] {} 被风控拦截: {}", user.getUsername(), sym0, decision.getReason());
                continue;
            }

            String side = "SHORT".equals(cand.get("direction")) ? "SELL" : "BUY";
            double price = getDouble(cand, "current_price");
            double qty = price > 0 ? openMargin * leverage / price : 0;
            if (qty <= 0) continue;

            double notional = openMargin * leverage;
            LiquidityChecker.DepthResult depth = liquidityChecker.check(sym0, side, notional);
            if (!depth.isOk()) {
                cand.put("unopen_reason", "深度拒绝: " + depth.getReason());
                log.info("[auto-open:{}] {} 深度不足: {}", user.getUsername(), sym0, depth.getReason());
                continue;
            }

            try {
                fapi.setLeverage(sym0, leverage);
                fapi.newOrder(sym0, side, qty);
                opened.add(cand.get("symbol").toString());
                avail -= openMargin;

                // 记录持仓
                String strategy = cand.get("strategy").toString();
                String sk = strategy.toLowerCase();
                Map<String, Object> sp = params.get(sk);
                double tpRatio = sp != null ? getParamDouble(sp, "tp_ratio") : 0;
                double slRatio = sp != null ? getParamDouble(sp, "sl_ratio") : 0;

                OpenPosition op = new OpenPosition();
                op.setUserId(userId);
                op.setSymbol(sym0);
                op.setStrategy(strategy);
                op.setDirection(cand.get("direction").toString());
                op.setQty(BigDecimal.valueOf(qty));
                op.setEntryPrice(BigDecimal.valueOf(price));
                op.setLeverage(leverage);
                op.setTpRatio(BigDecimal.valueOf(tpRatio));
                op.setSlRatio(BigDecimal.valueOf(slRatio));
                op.setStatus("OPEN");
                op.setOpenedAt(LocalDateTime.now());
                openPositionMapper.insert(op);

                held.add(sym0);
            } catch (Exception e) {
                log.error("[auto-trade:{}] {} 开仓失败: {}", user.getUsername(), sym0, e.getMessage());
            }
        }
        if (!opened.isEmpty()) {
            log.info("[auto-trade:{}] 开仓 {}", user.getUsername(), opened);
        }
    }

    // ==================== Update Account & Positions ====================

    @SuppressWarnings("unchecked")
    private void updateAccountAndPositions(RuntimeState rt, User user) {
        try {
            JsonNode acct = fapi.account();
            rt.accountTotalAssets = new BigDecimal(acct.get("totalMarginBalance").asText());
            rt.availableMargin = new BigDecimal(acct.get("availableBalance").asText());

            // 拉取实时行情，用于更新持仓当前价
            Map<String, Double> priceMap = new HashMap<>();
            try {
                List<Map<String, Object>> tickers = fapi.allTickers();
                for (Map<String, Object> t : tickers) {
                    Object sym = t.get("symbol");
                    Object price = t.get("lastPrice");
                    if (sym != null && price != null) {
                        priceMap.put(sym.toString(), Double.parseDouble(price.toString()));
                    }
                }
            } catch (Exception e) {
                // ticker 拉取失败不影响持仓展示，用 entryPrice 兜底
            }

            List<Map<String, Object>> posList = new ArrayList<>();
            JsonNode positionsNode = acct.get("positions");
            if (positionsNode != null && positionsNode.isArray()) {
                for (JsonNode p : positionsNode) {
                    double amt = p.get("positionAmt").asDouble();
                    if (amt == 0) continue;
                    String rawSym = p.get("symbol").asText();
                    String sym = normSymbol(rawSym);
                    double mg = p.get("initialMargin").asDouble();
                    double pnl = p.get("unrealizedProfit").asDouble();
                    // 实时当前价：优先用 ticker lastPrice，兜底用 entryPrice
                    double curPrice = priceMap.getOrDefault(rawSym, p.get("entryPrice").asDouble());
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("symbol", sym);
                    pos.put("direction", amt > 0 ? "LONG" : "SHORT");
                    pos.put("amount", Math.abs(amt));
                    pos.put("margin", mg);
                    pos.put("leverage", p.get("leverage").asInt());
                    pos.put("open_time", "");
                    pos.put("open_reason", "");
                    pos.put("entry_price", p.get("entryPrice").asDouble());
                    pos.put("current_price", curPrice);
                    pos.put("pnl", pnl);
                    pos.put("pnl_ratio", mg != 0 ? pnl / mg * 100 : 0);
                    posList.add(pos);
                }
            }
            rt.positions = posList;
        } catch (Exception e) {
            log.warn("[scan:{}] 更新账户失败: {}", user.getUsername(), e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private Set<String> getExcludedSet(User user) {
        List<ExcludedSymbol> excluded = excludedSymbolMapper.findByUserId(user.getId());
        Set<String> set = new HashSet<>();
        for (ExcludedSymbol e : excluded) {
            set.add(e.getSymbol());
        }
        return set;
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

    /** 计算RSI(14): 用K线收盘价数组 */
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

    private static String normSymbol(String sym) {
        if (sym.endsWith("USDT")) {
            return sym.substring(0, sym.length() - 4) + "/USDT";
        }
        return sym;
    }
}
