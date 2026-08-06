package com.yaob.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaob.entity.StrategyConfig;
import com.yaob.mapper.StrategyConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
public class StrategyService {

    @Autowired
    private StrategyConfigMapper strategyConfigMapper;
    @Autowired
    private ObjectMapper objectMapper;

    // 策略类型描述
    public static final Map<String, String> STRATEGY_TYPES = new LinkedHashMap<>();
    static {
        STRATEGY_TYPES.put("A", "空");
        STRATEGY_TYPES.put("B", "空");
        STRATEGY_TYPES.put("C", "多");
        STRATEGY_TYPES.put("D", "空");
        STRATEGY_TYPES.put("E", "多");
        STRATEGY_TYPES.put("F", "斐波那契双向");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getStrategyParams(Long userId) {
        List<StrategyConfig> configs = strategyConfigMapper.findByUserId(userId);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        // Use defaults from UserService
        for (String sk : UserService.getDefaultParamsMap().keySet()) {
            result.put(sk.toLowerCase(), new LinkedHashMap<>(UserService.getDefaultParamsMap().get(sk)));
        }
        for (StrategyConfig c : configs) {
            String sk = c.getStrategy().toUpperCase();
            Map<String, Object> target = result.get(sk.toLowerCase());
            if (target == null) {
                target = new LinkedHashMap<>();
                result.put(sk.toLowerCase(), target);
            }
            // 策略元信息（类型/描述）持久化在 strategy_type / description 字段
            if (c.getStrategyType() != null && !c.getStrategyType().isBlank()) {
                target.put("strategy_type", c.getStrategyType());
            }
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                target.put("description", c.getDescription());
            }
            if (c.getParamsJson() != null && !c.getParamsJson().isBlank()) {
                try {
                    Map<String, Object> params = objectMapper.readValue(c.getParamsJson(),
                            new TypeReference<Map<String, Object>>() {});
                    for (String pk : params.keySet()) {
                        target.put(pk, params.get(pk));
                    }
                } catch (Exception e) {
                    log.warn("解析策略 {} 参数JSON失败: {}", sk, e.getMessage());
                }
            }
            if (c.getTpRatio() != null) target.put("tp_ratio", c.getTpRatio().doubleValue());
            if (c.getSlRatio() != null) target.put("sl_ratio", c.getSlRatio().doubleValue());
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
        StrategyConfig config = strategyConfigMapper.findByUserIdAndStrategy(userId, sk);
        if (config == null) throw new RuntimeException("策略配置不存在");
        config.setEnabled(!Boolean.TRUE.equals(config.getEnabled()));
        strategyConfigMapper.updateById(config);
    }

    public void addStrategy(Long userId, String strategy) {
        addStrategy(userId, strategy, null, null, null);
    }

    public void addStrategy(Long userId, String strategy, Map<String, Object> initialParams) {
        addStrategy(userId, strategy, initialParams, null, null);
    }

    public void addStrategy(Long userId, String strategy, Map<String, Object> initialParams, String type, String description) {
        String sk = strategy.toUpperCase();
        if (sk.length() != 1 || !sk.matches("[A-Z]"))
            throw new RuntimeException("策略只能用单个字母 A-Z");
        StrategyConfig existing = strategyConfigMapper.findByUserIdAndStrategy(userId, sk);
        if (existing != null) throw new RuntimeException("策略 " + sk + " 已存在");
        Map<String, Object> params = (initialParams == null || initialParams.isEmpty())
                ? new LinkedHashMap<>() : new LinkedHashMap<>(initialParams);
        Object tp = params.get("tp_ratio");
        Object sl = params.get("sl_ratio");
        StrategyConfig config = new StrategyConfig();
        config.setUserId(userId);
        config.setStrategy(sk);
        config.setEnabled(false);
        config.setTpRatio(tp instanceof Number ? java.math.BigDecimal.valueOf(((Number) tp).doubleValue()) : java.math.BigDecimal.ZERO);
        config.setSlRatio(sl instanceof Number ? java.math.BigDecimal.valueOf(((Number) sl).doubleValue()) : java.math.BigDecimal.ZERO);
        config.setStrategyType(type);
        config.setDescription(description);
        try {
            config.setParamsJson(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            config.setParamsJson("{}");
        }
        strategyConfigMapper.insert(config);
    }

    public void deleteStrategy(Long userId, String strategy) {
        String sk = strategy.toUpperCase();
        StrategyConfig config = strategyConfigMapper.findByUserIdAndStrategy(userId, sk);
        if (config == null) throw new RuntimeException("策略 " + sk + " 不存在");
        strategyConfigMapper.deleteById(config.getId());
    }

    public Map<String, Boolean> getStrategyStates(Long userId) {
        List<StrategyConfig> configs = strategyConfigMapper.findByUserId(userId);
        Map<String, Boolean> states = new LinkedHashMap<>();
        // Defaults
        states.put("A", true);
        states.put("B", false);
        states.put("C", false);
        states.put("D", false);
        states.put("E", true);
        states.put("F", true);
        for (StrategyConfig c : configs) {
            states.put(c.getStrategy().toUpperCase(), c.getEnabled());
        }
        return states;
    }
}
