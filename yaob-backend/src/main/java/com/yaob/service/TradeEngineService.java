package com.yaob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaob.entity.*;
import com.yaob.config.CryptoUtil;
import com.yaob.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.yaob.service.TradeMath.*;

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
    private ExcludedSymbolMapper excludedSymbolMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private PositionCloseService positionCloseService;
    @Autowired
    private StrategyDetectorService strategyDetector;

    /** 用户并行扫描并发度（可选，application.yml 可覆盖） */
    @org.springframework.beans.factory.annotation.Value("${trade.scan.parallel:}")
    private String maxUserScanParallel = "";

    // 运行时状态: userId -> runtime
    private final Map<Long, RuntimeState> runtimeMap = new ConcurrentHashMap<>();

    /** 止损冷却: symbol -> 冷却截止时间戳(epoch ms)。止损平仓后短期内禁止再次开仓, 避免同一币反复止损(PROM坏洞)。 */
    private final Map<String, Long> slCooldownEnds = new ConcurrentHashMap<>();
    private static final long SL_COOLDOWN_MS = 30 * 60 * 1000L; // 30分钟

    /**
     * 候选池检测线程池（复用，避免每次扫描新建/销毁 16-48 线程）。
     * 懒加载：TradeEngineService 为长生命周期单例，池常驻，跨扫描复用。
     * 线程数按 CPU 核数在 16-48 之间钳制，与币安/代理吞吐匹配。
     */
    private volatile ExecutorService detectPool;

    private ExecutorService getDetectPool() {
        ExecutorService pool = detectPool;
        if (pool == null) {
            synchronized (this) {
                pool = detectPool;
                if (pool == null) {
                    int threads = Math.min(48, Math.max(16, Runtime.getRuntime().availableProcessors()));
                    pool = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "yaob-detect-pool");
                        t.setDaemon(true);
                        return t;
                    });
                    detectPool = pool;
                }
            }
        }
        return pool;
    }

    /**
     * 用户扫描并行线程池（复用常驻）。
     * 多用户架构：每轮扫描并行处理多个用户的 runScan，而非串行逐个。
     * 并发度有上限（默认 maxUserScanParallel），避免多用户同时调币安账户接口触发限流；
     * 行情走 Redis 缓存（2分钟TTL）不重复拉。
     */
    private volatile ExecutorService scanPool;

    private ExecutorService getScanPool() {
        ExecutorService pool = scanPool;
        if (pool == null) {
            synchronized (this) {
                pool = scanPool;
                if (pool == null) {
                    int threads = getMaxUserScanParallel();
                    pool = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "yaob-user-scan");
                        t.setDaemon(true);
                        return t;
                    });
                    scanPool = pool;
                }
            }
        }
        return pool;
    }

    /** 用户扫描并行并发度：4-32，默认 min(8, CPU核数)。@Value 可覆盖 */
    private int getMaxUserScanParallel() {
        int cpu = Runtime.getRuntime().availableProcessors();
        int def = Math.max(4, Math.min(8, cpu));
        try {
            int cfg = Integer.parseInt(maxUserScanParallel);
            return Math.max(4, Math.min(32, cfg));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 单日回撤熔断阈值（相对账户总资产的百分比）。
     * 当日亏损 <= -(总资产 * 该比例) 时触发熔断，停止开仓。
     * 统一口径：runScan() 的实时熔断状态与 autoOpenPositions() 的开仓熔断检查共用同一个值。
     */
    private static final double DAILY_LOSS_CIRCUIT_BREAKER_RATIO = 0.05; // 5% [v3.4 放宽熔断, 配合风控]

    /** 解密并返回指定用户的币安 API 密钥对（无密钥返回 null 表示模拟模式） */
    private String[] apiKeysOf(User user) {
        if (user == null || user.getBinanceApiKey() == null || user.getBinanceApiKey().isBlank()) return null;
        try {
            String apiKey = cryptoUtil.decrypt(user.getBinanceApiKey());
            String apiSecret = cryptoUtil.decrypt(user.getBinanceApiSecret());
            if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) return null;
            return new String[]{apiKey, apiSecret};
        } catch (Exception e) {
            return null;
        }
    }

    public static class RuntimeState {
        public String scannerStatus = "⏳ 倒计时";
        public double lastScanDuration = 0.0;
        public long nextScanTimestamp = System.currentTimeMillis() / 1000 + 120;
        public long scanStartTimestamp = 0;
        public BigDecimal accountTotalAssets = BigDecimal.ZERO;
        public BigDecimal availableMargin = BigDecimal.ZERO;
        public List<Map<String, Object>> candidatePool = new ArrayList<>();
        public List<Map<String, Object>> positions = new ArrayList<>();
        public double dailyPnl = 0;
        public double realizedPnl = 0;
        public double unrealizedPnl = 0;
        public boolean circuitBreaker = false;
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
        STAT.put("G", "日内多空三重过滤");
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
                        String apiKey = cryptoUtil.decrypt(user.getBinanceApiKey());
                        String apiSecret = cryptoUtil.decrypt(user.getBinanceApiSecret());
                        if (apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank()) {
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
                if (traders.isEmpty()) {
                    Thread.sleep(120_000);
                    continue;
                }
                // 并行扫描多个用户（多用户架构），并发度受控避免币安账户接口限流；
                // 行情走 Redis 缓存不重复拉。所有用户扫完后统一休眠再进入下一轮。
                ExecutorService pool = getScanPool();
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (User user : traders) {
                    futures.add(pool.submit(() -> {
                        try {
                            runScan(user);
                        } catch (Exception e) {
                            log.error("[scan:{} 扫描异常", user.getUsername(), e);
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try { f.get(); } catch (Exception e) { /* 单个用户失败不影响整体 */ }
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
        // 刷新BTC趋势状态(大盘过滤器)
        strategyDetector.refreshBtcTrend();
        rt.scanStartTimestamp = System.currentTimeMillis() / 1000;
        long startTime = System.currentTimeMillis();

        try {
            // 解密当前用户 API 密钥（可能为空，不影响公开行情接口）
            String apiKey = user.getBinanceApiKey() != null ? cryptoUtil.decrypt(user.getBinanceApiKey()) : "";
            String apiSecret = user.getBinanceApiSecret() != null ? cryptoUtil.decrypt(user.getBinanceApiSecret()) : "";
            boolean hasKey = apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();

            // 获取 24h ticker 全市场数据
            log.info("[scan:{}] 开始获取币安行情...", user.getUsername());
            List<Map<String, Object>> tickerList = fapi.allTickers();
            Map<String, Map<String, Object>> tickers = new HashMap<>();
            for (Map<String, Object> t : tickerList) {
                Object sym = t.get("symbol");
                if (sym != null) {
                    tickers.put(sym.toString(), t);
                }
            }
            log.info("[scan:{}] 获取到 {} 个交易对行情", user.getUsername(), tickers.size());

            // 先更新账户资产和持仓（不用等候选池跑完）
            if (hasKey) {
                updateAccountAndPositions(rt, user);
            }

            // 生成候选池
            List<Map<String, Object>> pool = candidates(tickers, user, params, states);
            rt.candidatePool = pool;

            // 更新当日盈亏和熔断状态
            double dailyLoss = getDailyLoss(user, rt);
            rt.dailyPnl = dailyLoss;
            double totalAssets = rt.accountTotalAssets != null ? rt.accountTotalAssets.doubleValue() : 0;
            rt.circuitBreaker = totalAssets > 0 && dailyLoss <= -(totalAssets * DAILY_LOSS_CIRCUIT_BREAKER_RATIO);

            // 自动交易
            if (Boolean.TRUE.equals(user.getAutoTradeEnabled())
                    && user.getBinanceApiKey() != null && !user.getBinanceApiKey().isBlank()
                    && hasKey) {

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
        // 候选币暂存（并发），按 symbol -> sig 收集
        List<Map<String, Object>> concurrentPool = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 固定线程池并发拉K线/检测，大幅降低扫描耗时（主因：D/E/F/G 逐币串行请求币安K线）
        // 币安/代理吞吐有限，但并发能显著压缩冷启动全量扫描时间。
        // 线程池为类级复用（getDetectPool），避免每次扫描新建/销毁线程。
        ExecutorService detectPool = getDetectPool();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : tickers.entrySet()) {
            if (count++ > 600) break;
            final String sym = entry.getKey();
            final Map<String, Object> tick = entry.getValue();

            // 快速 tiker 过滤（无网络请求）放主线程预判，不合格的直接跳过不提交任务
            if (!sym.endsWith("USDT")) continue;
            // excluded 集合已按分类处理：手动黑名单无条件排除，自动(大市值)名单由 getExcludedSet 内按开关过滤
            if (excluded.contains(sym)) continue;
            double qv0 = getDouble(tick, "quoteVolume");
            if (qv0 == 0 || qv0 < minVol) continue;

            boolean needsKlines0 = false;
            for (String sk : new String[]{"D", "E", "F", "G"}) {
                if (Boolean.TRUE.equals(states.get(sk))) { needsKlines0 = true; break; }
            }
            if (needsKlines0) {
                double klineVolMin0 = Double.MAX_VALUE;
                boolean hasG = false;
                for (String sk : new String[]{"D", "E", "F", "G"}) {
                    if (!Boolean.TRUE.equals(states.get(sk))) continue;
                    if ("G".equals(sk)) hasG = true;
                    Map<String, Object> pp = params.get(sk);
                    if (pp == null) continue;
                    Object v = pp.get("vol_min");
                    double vv = v instanceof Number ? ((Number) v).doubleValue() : 1e7;
                    if (vv < klineVolMin0) klineVolMin0 = vv;
                }
                double priceChange = getDouble(tick, "priceChangePercent");
                // G 策略(日内三罉过滤)只对有明显趋势/波动的币有价值：
                // 完全横盘的币拉K线也是浪费。启用 G 时要求 24h 涨跌幅更明显，避免滤死大部分币又保证不拉废请求。
                double minActivity = hasG ? 0.3 : 0.1;
                if (qv0 < klineVolMin0 || Math.abs(priceChange) < minActivity) continue;
            }

            // 提交并行检测任务（每币独立，无共享可变状态）
            futures.add(detectPool.submit(() -> {
                List<Map<String, Object>> localSig = new ArrayList<>();
                for (String sk : new String[]{"A", "B", "C", "D", "E", "F", "G"}) {
                    if (!Boolean.TRUE.equals(states.get(sk))) continue;
                    Map<String, Object> p = params.get(sk);
                    if (p == null) continue;
                    Map<String, Object> sig = null;
                    try {
                        sig = strategyDetector.check(sk, sym, tick, p);
                    } catch (Exception e) {
                        // skip
                    }
                    if (sig != null) {
                        sig.put("symbol", normSymbol(sym));
                        sig.put("current_price", getDouble(tick, "lastPrice"));
                        double pr = getDouble(tick, "priceChangePercent") / 100.0;
                        // 大盘降权: BTC强势时做空信号降权, BTC弱势时做多信号降权(顺大盘优先)
                        // [方案1] G策略自带多空三重过滤(EMA趋势+量价+RSI), 不参与全局顺大盘降权, 避免被过度压制
                        String dir = (String) sig.get("direction");
                        boolean btcBull = strategyDetector.isBtcBullish();
                        boolean isG = "G".equals(sk);
                        if (!isG) {
                            if ("SHORT".equals(dir) && btcBull) {
                                pr *= 0.5;   // BTC强势 -> 做空降权
                            } else if ("LONG".equals(dir) && !btcBull) {
                                pr *= 0.5;   // BTC弱势 -> 做多降权
                            }
                        }
                        sig.put("priority", pr);
                        sig.put("trigger_time", LocalDateTime.now().toString());
                        sig.put("unopen_reason", "等待开仓");
                        localSig.add(sig);
                        break; // 每币取一个信号
                    }
                }
                if (!localSig.isEmpty()) {
                    concurrentPool.addAll(localSig);
                }
            }));
        }
        // 等待所有检测完成
        for (java.util.concurrent.Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { /* 单任务失败不影响整体 */ }
        }
        // 复用线程池，此处不 shutdown（池为类级常驻）
        pool.addAll(concurrentPool);

        // 排序: 全局按 priority 降序混排(不再 SHORT 全排前面把 LONG 挤出版外 → 候选池 10 名内多空自然分布)
        List<Map<String, Object>> result = new ArrayList<>(pool);
        result.sort((a, b) -> Double.compare(getDouble(b, "priority"), getDouble(a, "priority")));
        if (result.size() > 10) result = result.subList(0, 10);
        return result;
    }

    // ==================== Auto Close Positions =====================

    @Transactional
    @SuppressWarnings("unchecked")
    private void autoClosePositions(User user, Map<String, Map<String, Object>> tickers,
                                     Map<String, Map<String, Object>> params) {
        Long userId = user.getId();
        List<OpenPosition> openPositions = openPositionMapper.findOpenByUserId(userId);
        if (openPositions.isEmpty()) return;
        String[] keys = apiKeysOf(user);
        if (keys == null) return;

        // 获取实时持仓
        Map<String, JsonNode> livePositions = new HashMap<>();
        boolean accountOk = false;
        try {
            JsonNode acct = fapi.account(keys[0], keys[1]);
            accountOk = true;
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
            log.warn("[auto-close:{}] 获取持仓失败, 跳过本轮平仓判断: {}", user.getUsername(), e.getMessage());
        }

        // account 拉取失败时绝对不能误判仓位状态，直接跳过本轮
        if (!accountOk) return;

        for (OpenPosition pos : openPositions) {
            // 统一用币安全原始格式(去斜杠)做匹配，避免 slash/raw 格式不一致导致误判"无持仓"而错误清理记录
            String sym0 = rawSymbol(pos.getSymbol());
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
                String sk = pos.getStrategy().toUpperCase();
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
            // 双向持仓模式(Hedge): 平仓必须带对应的 positionSide (LONG/SHORT); 单向传 BOTH
            String positionSide = isHedge(user) ? ("LONG".equalsIgnoreCase(pos.getDirection()) ? "LONG" : "SHORT") : "BOTH";

            // 触发止盈或止损
            boolean shouldClose = false;
            String reason = null;
            if (tp > 0 && ratio >= tp) {
                log.info("[auto-close:{}] {} 止盈 ratio={}% >= tp={}", user.getUsername(), sym0, String.format("%.2f", ratio), tp);
                shouldClose = true;
                reason = "tp";
            } else if (sl < 0 && ratio <= sl) {
                log.info("[auto-close:{}] {} 止损 ratio={}% <= sl={}", user.getUsername(), sym0, String.format("%.2f", ratio), sl);
                shouldClose = true;
                reason = "sl";
            }

            // 持仓超时检查（最长持仓48小时）
            if (pos.getOpenedAt() != null) {
                long holdHours = java.time.Duration.between(pos.getOpenedAt(), LocalDateTime.now()).toHours();
                if (holdHours >= 48) {
                    log.info("[auto-close:{}] {} 持仓超时{}h, 自动平仓", user.getUsername(), sym0, holdHours);
                    shouldClose = true;
                    reason = "timeout";
                }
            }

            if (shouldClose) {
                try {
                    fapi.closePosition(sym0, qty, side, positionSide, keys[0], keys[1]);
                    log.info("[auto-close:{}] {} 已平 {} ({})", user.getUsername(), sym0, qty, side);
                    
                    // 部分成交处理：平仓后重新查询实际持仓，判断是否完全平仓
                    boolean fullyClosed = true;
                    double remainingQty = 0;
                    try {
                        JsonNode acctAfter = fapi.account(keys[0], keys[1]);
                        JsonNode posAfter = acctAfter.get("positions");
                        if (posAfter != null && posAfter.isArray()) {
                            for (JsonNode p : posAfter) {
                                if (sym0.equals(p.get("symbol").asText())) {
                                    double amtAfter = p.get("positionAmt").asDouble();
                                    if (Math.abs(amtAfter) > 0.0001) {
                                        fullyClosed = false;
                                        remainingQty = Math.abs(amtAfter);
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[auto-close:{}] {} 平仓后查询持仓失败，按已平处理: {}", user.getUsername(), sym0, e.getMessage());
                    }
                    
                    if (fullyClosed) {
                        // 完全平仓，归档记录
                        // [止损冷却] 止损平仓后记录该币冷却截止时间, 短期内禁止再次开仓(避免同一币反复止损)
                        if ("sl".equals(reason)) {
                            slCooldownEnds.put(sym0, System.currentTimeMillis() + SL_COOLDOWN_MS);
                            log.info("[auto-close:{}] {} 止损冷却计时 {} 分钟", user.getUsername(), sym0, SL_COOLDOWN_MS / 60000);
                        }
                        closePositionRecord(pos, BigDecimal.valueOf(last), reason,
                                BigDecimal.valueOf(pnl), BigDecimal.valueOf(ratio), userId);
                    } else {
                        // 部分成交：更新持仓数量，保持 OPEN 状态，下次扫描继续处理
                        log.warn("[auto-close:{}] {} 部分成交, 剩余 {} 张, 保持持仓", user.getUsername(), sym0, remainingQty);
                        pos.setQty(BigDecimal.valueOf(remainingQty));
                        openPositionMapper.updateById(pos);
                    }
                } catch (Exception e) {
                    log.error("[auto-close:{}] {} 平仓失败: {}", user.getUsername(), sym0, e.getMessage());
                }
            }
        }
    }

    @Transactional
    private void closePositionRecord(OpenPosition pos, BigDecimal closePrice, String reason,
                                      BigDecimal pnl, BigDecimal pnlRatio, Long userId) {
        // 委托给独立的 PositionCloseService：其 @Transactional 能被 Spring 代理正确拦截，
        // 保证关闭持仓 + 归档流水 + 更新统计三者原子性（自调用不生效，故拆出）。
        positionCloseService.closePositionRecord(pos, closePrice, reason, pnl, pnlRatio, userId);
    }

    public void updateStrategyStats(Long userId, String strategy, String reason, BigDecimal pnl) {
        positionCloseService.updateStrategyStats(userId, strategy, reason, pnl);
    }

    // ==================== Auto Open Positions ====================

    private double getDailyLoss(User user, RuntimeState rt) {
        Long userId = user.getId();
        double realized = 0;
        try {
            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
            // 由数据库直接聚合当日已实现盈亏，避免全部历史流水拉回内存过滤
            BigDecimal sum = tradeHistoryMapper.sumRealizedPnlSince(userId, todayStart);
            if (sum != null) {
                realized = sum.doubleValue();
            }
        } catch (Exception e) {
            log.warn("查询当日已实现亏损失败: {}", e.getMessage());
        }
        // 当前实盘持仓的浮动盈亏（unrealizedProfit）
        double unrealized = 0;
        try {
            String[] keys = apiKeysOf(user);
            if (keys != null) {
                JsonNode acct = fapi.account(keys[0], keys[1]);
                if (acct != null && acct.has("positions") && acct.get("positions").isArray()) {
                    for (JsonNode p : acct.get("positions")) {
                        double amt = p.has("positionAmt") ? p.get("positionAmt").asDouble() : 0;
                        if (amt == 0) continue;
                        double upnl = p.has("unrealizedProfit") && !p.get("unrealizedProfit").isNull()
                                ? p.get("unrealizedProfit").asDouble() : 0;
                        unrealized += upnl;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询当日浮动盈亏失败: {}", e.getMessage());
        }
        if (rt != null) {
            rt.realizedPnl = realized;
            rt.unrealizedPnl = unrealized;
        }
        return realized + unrealized;
    }

    // 是否双向持仓模式(Hedge): 用户 positionMode=hedge 时启用 LONG/SHORT 方向单
    private boolean isHedge(User user) {
        return user != null && "hedge".equalsIgnoreCase(user.getPositionMode());
    }

    @SuppressWarnings("unchecked")
    private void autoOpenPositions(User user, RuntimeState rt,
                                    List<Map<String, Object>> pool,
                                    Map<String, Map<String, Object>> params,
                                    Map<String, Boolean> states) {
        Long userId = user.getId();
        String[] keys = apiKeysOf(user);
        if (keys == null) return;

        // 单日亏损熔断检查
        double dailyLoss = getDailyLoss(user, rt);
        double totalAssets = rt.accountTotalAssets != null ? rt.accountTotalAssets.doubleValue() : 0;
        double maxDailyLoss = totalAssets * DAILY_LOSS_CIRCUIT_BREAKER_RATIO; // 2% 熔断
        if (totalAssets > 0 && dailyLoss <= -maxDailyLoss) {
            log.warn("[auto-trade:{}] 单日亏损 {}U 超过熔断线 {}U, 停止开仓", user.getUsername(), String.format("%.2f", dailyLoss), String.format("%.2f", -maxDailyLoss));
            return;
        }

        // 总持仓上限检查
        int currentPositions = openPositionMapper.findOpenByUserId(userId).size();
        int maxTotalPositions = 20; // 最大同时持仓20个(扩量)

        // 当前持仓币种集合
        Set<String> held = new HashSet<>();
        boolean accountOk2 = false;
        try {
            JsonNode acct = fapi.account(keys[0], keys[1]);
            accountOk2 = true;
            JsonNode positionsNode = acct.get("positions");
            if (positionsNode != null && positionsNode.isArray()) {
                for (JsonNode p : positionsNode) {
                    if (p.get("positionAmt").asDouble() != 0) {
                        held.add(p.get("symbol").asText());
                    }
                }
            }
        } catch (Exception e) {
            // account 拉取失败时不阻断开仓，但 held 为空可能导致重复开仓
            // 补充：从数据库 open_positions 获取已持仓币种作为兜底
            log.warn("[auto-trade:{}] 获取实盘持仓失败, 从数据库补全: {}", user.getUsername(), e.getMessage());
        }
        // account 拉取失败时从数据库补全已持仓币种，防止重复开仓
        if (!accountOk2) {
            for (OpenPosition op : openPositionMapper.findOpenByUserId(userId)) {
                held.add(rawSymbol(op.getSymbol()));
            }
        }

        double avail = rt.availableMargin != null ? rt.availableMargin.doubleValue() : 0;
        double openMargin = user.getOpenMargin() != null ? user.getOpenMargin().doubleValue() : 5.0;
        int leverage = user.getLeverage() != null ? user.getLeverage() : 5;

        List<String> opened = new ArrayList<>();
        int maxOpen = Math.min(5, Math.min(pool.size(), maxTotalPositions - currentPositions)); // [扩量] 每轮开仓5 更激进
        if (currentPositions >= maxTotalPositions) {
            log.info("[auto-trade:{}] 已达最大持仓数{}, 跳过开仓", user.getUsername(), maxTotalPositions);
            return;
        }
        for (int i = 0; i < maxOpen; i++) {
            Map<String, Object> cand = pool.get(i);
            String sym0 = rawSymbol(cand.get("symbol").toString());

            if (held.contains(sym0)) continue;

            // [止损冷却] 该币止损平仓后处于冷却期内, 跳过开仓(避免同一币反复止损)
            Long end = slCooldownEnds.get(sym0);
            if (end != null && end > System.currentTimeMillis()) {
                cand.put("unopen_reason", String.format("止损冷却中(剩%d分)", (end - System.currentTimeMillis()) / 60000 + 1));
                log.info("[auto-trade:{}] {} 止损冷却中, 跳过开仓", user.getUsername(), sym0);
                continue;
            }

            // 保证金门槛
            if (avail < openMargin) {
                cand.put("unopen_reason", String.format("保证金不足(%.2f<%.2f)", avail, openMargin));
                continue;
            }

            // [v3.4 单笔风险上限] 本仓最大止损额 = openMargin * |sl_ratio|% 
            // 方案B：上限 = max(总资产*1%, openMargin档位对应风险) —— 保证单仓能按用户设定的开仓档位正常开，
            // 仅当 openMargin 档位远超账户承受能力(总资产过小)时才拦截，避免小账户被 1% 上限误伤导致永不开仓。
            double totalAssetsNow = rt.accountTotalAssets != null ? rt.accountTotalAssets.doubleValue() : 0;
            String riskStrat = String.valueOf(cand.get("strategy"));
            Map<String, Object> riskP = params.get(riskStrat.toUpperCase());
            double riskSlRatio = 0;
            if (riskP != null) riskSlRatio = getParamDouble(riskP, "sl_ratio");
            double thisRisk = openMargin * Math.abs(riskSlRatio) / 100.0;
            double minRiskFloor = openMargin * Math.abs(riskSlRatio) / 100.0; // openMargin 档位保底风险
            double maxSingleRisk = Math.max(totalAssetsNow * 0.01, minRiskFloor);
            if (totalAssetsNow > 0 && thisRisk > maxSingleRisk) {
                cand.put("unopen_reason", String.format("单笔风险超限(%s>%s)", fmt(thisRisk), fmt(maxSingleRisk)));
                log.warn("[auto-trade:{}] {} 单笔风险超限 止损额={} > 上限={}, 跳过", user.getUsername(), sym0, fmt(thisRisk), fmt(maxSingleRisk));
                continue;
            }

            String side = "SHORT".equals(cand.get("direction")) ? "SELL" : "BUY";            double price = getDouble(cand, "current_price");

            try {
                // 资金费率过滤：费率绝对值超过0.1%跳过（避免极端费率）
                try {
                    JsonNode fr = fapi.fundingRate(sym0);
                    if (fr != null && fr.has("lastFundingRate")) {
                        double rate = Double.parseDouble(fr.get("lastFundingRate").asText());
                        if (Math.abs(rate) > 0.003) { // [v3.4 放宽费率门槛 0.1%->0.3%]
                            log.info("[auto-trade:{}] {} 资金费率过高, 跳过", user.getUsername(), sym0);
                            cand.put("unopen_reason", String.format("资金费率过高(%.4f)", rate));
                            continue;
                        }
                    }
                } catch (Exception e) {
                    // 获取费率失败不阻断
                }

                // 设置杠杆: 若配置杠杆超过该币种最大杠杆上限, 自动降级到上限(避免 FAPI 400 "Leverage N is not valid")
                int effLeverage = leverage;
                try {
                    int maxLev = fapi.getMaxLeverage(sym0, keys[0], keys[1]);
                    if (maxLev > 0 && leverage > maxLev) {
                        effLeverage = maxLev;
                        log.info("[auto-trade:{}] {} 配置杠杆{}x超上限{}x, 自动降级为{}x", user.getUsername(), sym0, leverage, maxLev, maxLev);
                    }
                } catch (Exception e) {
                    log.warn("[auto-trade:{}] 查询 {} 最大杠杆失败: {}", user.getUsername(), sym0, e.getMessage());
                }
                // 按实际生效杠杆计算数量, 保证保证金占用与 openMargin 一致
                double qty = price > 0 ? openMargin * effLeverage / price : 0;
                if (qty <= 0) continue;
                fapi.setLeverage(sym0, effLeverage, keys[0], keys[1]);
                // 双向持仓模式(Hedge): 开仓必须带对应的 positionSide (LONG/SHORT); 单向传 BOTH
                String posSide = isHedge(user) ? ("SHORT".equals(cand.get("direction")) ? "SHORT" : "LONG") : "BOTH";
                JsonNode orderResult = fapi.newOrder(sym0, side, qty, posSide, keys[0], keys[1]);
                // 尝试从订单响应获取实际成交价
                double actualPrice = price;
                if (orderResult != null && orderResult.has("avgPrice")) {
                    try {
                        actualPrice = Double.parseDouble(orderResult.get("avgPrice").asText());
                        if (actualPrice <= 0) actualPrice = price;
                    } catch (Exception e) { /* 用ticker价格兜底 */ }
                }
                opened.add(sym0);
                avail -= openMargin;
                rt.availableMargin = BigDecimal.valueOf(avail);

                // 记录持仓
                String strategy = cand.get("strategy").toString();
                String sk = strategy.toUpperCase();
                Map<String, Object> sp = params.get(sk);
                double tpRatio = sp != null ? getParamDouble(sp, "tp_ratio") : 0;
                double slRatio = sp != null ? getParamDouble(sp, "sl_ratio") : 0;

                OpenPosition op = new OpenPosition();
                op.setUserId(userId);
                op.setSymbol(sym0);
                op.setStrategy(strategy);
                op.setDirection(cand.get("direction").toString());
                op.setQty(BigDecimal.valueOf(qty));
                op.setEntryPrice(BigDecimal.valueOf(actualPrice));
                op.setLeverage(effLeverage);
                op.setTpRatio(BigDecimal.valueOf(tpRatio));
                op.setSlRatio(BigDecimal.valueOf(slRatio));
                op.setStatus("OPEN");
                op.setOpenedAt(LocalDateTime.now());
                openPositionMapper.insert(op);

                held.add(sym0);
                currentPositions++;
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
        String[] keys = apiKeysOf(user);
        if (keys == null) return;
        try {
            JsonNode acct = fapi.account(keys[0], keys[1]);
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

    /** 保留两位小数的金额格式化 */
    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /**
     * 获取用户黑名单，按分类拆分：
     * - manual（手动黑名单）：用户手动添加，无条件排除，不受开关控制
     * - large_cap（自动黑名单）：市值>阈值自动过滤，受 excludeLargeCap 开关控制
     */
    private Set<String> getExcludedSet(User user) {
        List<ExcludedSymbol> excluded = excludedSymbolMapper.findByUserId(user.getId());
        Set<String> set = new HashSet<>();
        for (ExcludedSymbol e : excluded) {
            // 手动黑名单无条件排除；自动黑名单(大市值)仍放入集合，由开关控制
            if ("manual".equals(e.getCategory()) || !Boolean.TRUE.equals(user.getExcludeLargeCap())) {
                set.add(e.getSymbol());
            }
        }
        return set;
    }

}
