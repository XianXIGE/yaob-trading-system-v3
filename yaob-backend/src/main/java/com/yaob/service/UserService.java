package com.yaob.service;

import com.yaob.common.BusinessException;
import com.yaob.config.CryptoProperties;
import com.yaob.util.AesEncryptor;
import com.yaob.dto.RegisterRequest;
import com.yaob.entity.ExcludedSymbol;
import com.yaob.entity.StrategyConfig;
import com.yaob.entity.User;
import com.yaob.mapper.ExcludedSymbolMapper;
import com.yaob.mapper.StrategyConfigMapper;
import com.yaob.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StrategyConfigMapper strategyConfigMapper;
    @Autowired
    private ExcludedSymbolMapper excludedSymbolMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CryptoProperties cryptoProperties;

    @Value("${admin.user:XJarvis}")
    private String adminUser;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AesEncryptor encryptor;

    private AesEncryptor enc() {
        if (encryptor == null) {
            encryptor = new AesEncryptor(cryptoProperties.getSecret());
        }
        return encryptor;
    }

    public String[] getDecryptedApiKeys(User user) {
        if (user == null) return new String[]{"", ""};
        String key = enc().decrypt(user.getBinanceApiKey() == null ? "" : user.getBinanceApiKey());
        String secret = enc().decrypt(user.getBinanceApiSecret() == null ? "" : user.getBinanceApiSecret());
        return new String[]{key, secret};
    }

    private static final Map<String, Map<String, Object>> DEFAULT_PARAMS = new LinkedHashMap<>();
    static {
        // A: 做空，24h涨幅在[gain_min, gain_max] + 成交额达标
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lookback_days", 66);
        a.put("gain_min", 0.36);
        a.put("gain_max", 0.50);
        a.put("vol_min", 1e7);
        a.put("tp_ratio", 800);
        a.put("sl_ratio", -20);
        DEFAULT_PARAMS.put("A", a);

        // B: 做空，当日涨幅>=gain_threshold + 成交额达标
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("gain_threshold", 0.38);
        b.put("vol_min", 1e7);
        b.put("tp_ratio", 60);
        b.put("sl_ratio", -20);
        DEFAULT_PARAMS.put("B", b);

        // C: 做多，从高点回撤>=drop_threshold + 成交额达标
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("lookback_days", 7);
        c.put("drop_threshold", 0.96);
        c.put("vol_min", 1e8);
        c.put("tp_ratio", 100);
        c.put("sl_ratio", -20);
        DEFAULT_PARAMS.put("C", c);

        // D: 做空，N分钟涨幅>=gain_threshold + 成交额达标
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("window_minutes", 5);
        d.put("gain_threshold", 0.05);
        d.put("vol_min", 1e7);
        d.put("tp_ratio", 60);
        d.put("sl_ratio", -20);
        DEFAULT_PARAMS.put("D", d);

        // E: 做多，冲高>=peak_gain后回落至<=retrace_target + 成交额达标
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("peak_gain_threshold", 0.50);
        e.put("retrace_target_gain", 0.10);
        e.put("vol_min", 1e7);
        e.put("tp_ratio", 1200);
        e.put("sl_ratio", -86);
        DEFAULT_PARAMS.put("E", e);

        // F: 斐波那契双向
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("lookback_hours", 48);
        f.put("fib_long", 0.786);
        f.put("fib_short", 0.618);
        f.put("tolerance_ratio", 0.1);
        f.put("vol_min", 3e7);
        f.put("tp_ratio", 10);
        f.put("sl_ratio", -15);
        DEFAULT_PARAMS.put("F", f);
    }

    // 默认策略启用状态: A=true, B=false, C=false, D=false, E=true, F=true
    private static final Map<String, Boolean> DEFAULT_STRATEGY_STATES = new LinkedHashMap<>();
    static {
        DEFAULT_STRATEGY_STATES.put("A", true);
        DEFAULT_STRATEGY_STATES.put("B", false);
        DEFAULT_STRATEGY_STATES.put("C", false);
        DEFAULT_STRATEGY_STATES.put("D", false);
        DEFAULT_STRATEGY_STATES.put("E", true);
        DEFAULT_STRATEGY_STATES.put("F", true);
    }

    // 默认大盘币列表
    public static final List<String> DEFAULT_LARGE_CAP = List.of(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT",
            "DOGEUSDT", "DOTUSDT", "LINKUSDT", "LTCUSDT", "BCHUSDT", "AVAXUSDT",
            "SHIBUSDT", "TONUSDT", "TRXUSDT", "UNIUSDT", "ATOMUSDT", "XLMUSDT",
            "FILUSDT", "SUIUSDT", "NEARUSDT", "APTUSDT", "ARBUSDT", "OPUSDT",
            "INJUSDT", "SEIUSDT", "HBARUSDT", "ICPUSDT", "RENDERUSDT", "WIFUSDT",
            "TRUMPUSDT", "1000PEPEUSDT", "ETCUSDT"
    );

    public User register(RegisterRequest req) {
        String username = req.getUsername().trim();
        String password = req.getPassword();
        if (username.isEmpty() || password == null || password.isEmpty()) {
            throw new BusinessException("用户名和密码不能为空");
        }
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setIsVip(false);
        user.setIsAdmin(username.equals(adminUser));
        user.setBinanceApiKey("");
        user.setBinanceApiSecret("");
        user.setAutoTradeEnabled(false);
        user.setMarginMode("isolated");
        user.setOpenMargin(new BigDecimal("5.00"));
        user.setLeverage(5);
        user.setExcludeLargeCap(true);
        userMapper.insert(user);

        // 初始化策略配置
        initDefaultStrategies(user.getId());
        // 初始化大盘币黑名单
        initDefaultExcluded(user.getId());

        return user;
    }

    public User login(String username, String password, HttpSession session) {
        User user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        return user;
    }

    public User getCurrentUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return null;
        // 每次从数据库重新读取，确保 VIP/权限状态实时
        return userMapper.selectById(userId);
    }

    public User findByUsername(String username) {
        return userMapper.findByUsername(username);
    }

    public User findById(Long id) {
        return userMapper.selectById(id);
    }

    public boolean isAdmin(User user) {
        return user != null && Boolean.TRUE.equals(user.getIsAdmin());
    }

    public boolean isVip(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsVip())) return false;
        if (user.getVipExpireAt() == null) return true; // null = 永久
        return user.getVipExpireAt().isAfter(LocalDateTime.now());
    }

    public void checkVip(User user) {
        if (!isVip(user)) {
            throw new BusinessException("请先升级为VIP会员");
        }
    }

    public void setApiKeys(Long userId, String apiKey, String apiSecret) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new BusinessException("请完整填写API Key和Secret");
        }
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setBinanceApiKey(enc().encrypt(apiKey.trim()));
        user.setBinanceApiSecret(enc().encrypt(apiSecret.trim()));
        userMapper.updateById(user);
        log.info("[security] userId={} API Key 已加密存储", userId);
    }

    public void clearApiKeys(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setBinanceApiKey("");
        user.setBinanceApiSecret("");
        userMapper.updateById(user);
    }

    public void toggleAutoTrade(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setAutoTradeEnabled(!Boolean.TRUE.equals(user.getAutoTradeEnabled()));
        userMapper.updateById(user);
    }

    public void toggleMarginMode(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setMarginMode("cross".equals(user.getMarginMode()) ? "isolated" : "cross");
        userMapper.updateById(user);
    }

    public void toggleExcludeLargeCap(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        user.setExcludeLargeCap(!Boolean.TRUE.equals(user.getExcludeLargeCap()));
        userMapper.updateById(user);
    }

    public void updateControl(Long userId, BigDecimal openMargin, Integer leverage) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        if (openMargin != null) user.setOpenMargin(openMargin);
        if (leverage != null) user.setLeverage(leverage);
        userMapper.updateById(user);
    }

    public void checkVipExpiry(User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsVip())) return;
        if (user.getVipExpireAt() != null && user.getVipExpireAt().isBefore(LocalDateTime.now())) {
            user.setIsVip(false);
            userMapper.updateById(user);
            log.info("用户 {} VIP 已过期", user.getUsername());
        }
    }

    public Map<String, Boolean> getStrategyStates(Long userId) {
        List<StrategyConfig> configs = strategyConfigMapper.findByUserId(userId);
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String s : DEFAULT_STRATEGY_STATES.keySet()) {
            states.put(s, DEFAULT_STRATEGY_STATES.get(s));
        }
        for (StrategyConfig c : configs) {
            states.put(c.getStrategy(), c.getEnabled());
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getStrategyParams(Long userId) {
        List<StrategyConfig> configs = strategyConfigMapper.findByUserId(userId);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        // Deep copy defaults
        for (String key : DEFAULT_PARAMS.keySet()) {
            result.put(key, new LinkedHashMap<>(DEFAULT_PARAMS.get(key)));
        }
        for (StrategyConfig c : configs) {
            String sk = c.getStrategy().toUpperCase();
            if (c.getParamsJson() != null && !c.getParamsJson().isBlank()) {
                try {
                    Map<String, Object> params = objectMapper.readValue(c.getParamsJson(),
                            new TypeReference<Map<String, Object>>() {});
                    Map<String, Object> merged = result.get(sk);
                    if (merged != null) {
                        for (String pk : params.keySet()) {
                            merged.put(pk, params.get(pk));
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析策略 {} 参数JSON失败: {}", sk, e.getMessage());
                }
            }
            // 覆盖 tp_ratio / sl_ratio
            if (c.getTpRatio() != null) result.get(sk).put("tp_ratio", c.getTpRatio().doubleValue());
            if (c.getSlRatio() != null) result.get(sk).put("sl_ratio", c.getSlRatio().doubleValue());
        }
        return result;
    }

    public void saveStrategyParams(Long userId, Map<String, Map<String, Object>> params) {
        for (String sk : params.keySet()) {
            StrategyConfig config = strategyConfigMapper.findByUserIdAndStrategy(userId, sk.toUpperCase());
            if (config == null) continue;
            Map<String, Object> p = params.get(sk);
            try {
                config.setParamsJson(objectMapper.writeValueAsString(p));
            } catch (Exception e) {
                log.warn("序列化策略 {} 参数失败", sk);
            }
            Object tp = p.get("tp_ratio");
            Object sl = p.get("sl_ratio");
            if (tp instanceof Number) config.setTpRatio(BigDecimal.valueOf(((Number) tp).doubleValue()));
            if (sl instanceof Number) config.setSlRatio(BigDecimal.valueOf(((Number) sl).doubleValue()));
            strategyConfigMapper.updateById(config);
        }
    }

    public void toggleStrategy(Long userId, String strategy) {
        String sk = strategy.toUpperCase();
        if (!DEFAULT_PARAMS.containsKey(sk)) {
            throw new BusinessException("未知策略");
        }
        StrategyConfig config = strategyConfigMapper.findByUserIdAndStrategy(userId, sk);
        if (config == null) {
            throw new BusinessException("策略配置不存在");
        }
        config.setEnabled(!Boolean.TRUE.equals(config.getEnabled()));
        strategyConfigMapper.updateById(config);
    }

    public List<User> findAllUsers() {
        return userMapper.selectList(null);
    }

    public void setVip(String username, boolean vip, int days) {
        User user = userMapper.findByUsername(username);
        if (user == null) throw new BusinessException("账号 " + username + " 不存在");
        if (username.equals(adminUser)) throw new BusinessException("不能修改管理员自身 VIP");
        user.setIsVip(vip);
        if (vip) {
            user.setVipExpireAt(days > 0 ? LocalDateTime.now().plusDays(days) : null);
        } else {
            user.setVipExpireAt(null);
        }
        userMapper.updateById(user);
    }

    public void deleteUser(String username) {
        User user = userMapper.findByUsername(username);
        if (user == null) throw new BusinessException("账号 " + username + " 不存在");
        if (username.equals(adminUser)) throw new BusinessException("不能删除管理员账号");
        userMapper.deleteById(user.getId());
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException("旧密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getDefaultParams(String strategy) {
        Map<String, Object> src = DEFAULT_PARAMS.get(strategy.toUpperCase());
        if (src == null) return null;
        return new LinkedHashMap<>(src);
    }

    public static Map<String, Map<String, Object>> getDefaultParamsMap() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (String key : DEFAULT_PARAMS.keySet()) {
            copy.put(key, new LinkedHashMap<>(DEFAULT_PARAMS.get(key)));
        }
        return copy;
    }

    private void initDefaultStrategies(Long userId) {
        for (String sk : DEFAULT_PARAMS.keySet()) {
            StrategyConfig config = new StrategyConfig();
            config.setUserId(userId);
            config.setStrategy(sk);
            config.setEnabled(DEFAULT_STRATEGY_STATES.get(sk));
            Map<String, Object> params = DEFAULT_PARAMS.get(sk);
            Object tp = params.get("tp_ratio");
            Object sl = params.get("sl_ratio");
            config.setTpRatio(BigDecimal.valueOf(((Number) tp).doubleValue()));
            config.setSlRatio(BigDecimal.valueOf(((Number) sl).doubleValue()));
            try {
                config.setParamsJson(objectMapper.writeValueAsString(params));
            } catch (Exception e) {
                config.setParamsJson("{}");
            }
            strategyConfigMapper.insert(config);
        }
    }

    private void initDefaultExcluded(Long userId) {
        for (String symbol : DEFAULT_LARGE_CAP) {
            ExcludedSymbol ex = new ExcludedSymbol();
            ex.setUserId(userId);
            ex.setSymbol(symbol);
            ex.setCategory("large_cap");
            excludedSymbolMapper.insert(ex);
        }
    }
}
