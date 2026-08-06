package com.yaob.service;

import com.yaob.config.RiskProperties;
import com.yaob.entity.OpenPosition;
import com.yaob.entity.User;
import com.yaob.mapper.OpenPositionMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class RiskManager {

    @Autowired
    private RiskProperties riskProps;
    @Autowired
    private OpenPositionMapper openPositionMapper;
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Data
    public static class RiskDecision {
        private boolean allowed;
        private String reason;
        public static RiskDecision allow() {
            RiskDecision d = new RiskDecision();
            d.allowed = true;
            d.reason = "OK";
            return d;
        }
        public static RiskDecision deny(String reason) {
            RiskDecision d = new RiskDecision();
            d.allowed = false;
            d.reason = reason;
            return d;
        }
    }

    public RiskDecision canOpen(User user, double availableMargin, int currentPositionCount,
                                double openMargin, int leverage) {
        int maxPos = riskProps.getMaxPositions();
        double maxTotalMargin = riskProps.getMaxTotalMargin();
        double dailyLossLimit = riskProps.getDailyLossLimit();
        double maxOpenMargin = riskProps.getMaxOpenMargin();
        int maxLeverage = riskProps.getMaxLeverage();

        if (leverage > maxLeverage) {
            return RiskDecision.deny(String.format("杠杆超限(%d > %d)", leverage, maxLeverage));
        }
        if (openMargin > maxOpenMargin) {
            return RiskDecision.deny(String.format("单笔保证金超限(%.2f > %.2f)", openMargin, maxOpenMargin));
        }
        if (currentPositionCount >= maxPos) {
            return RiskDecision.deny(String.format("持仓数已达上限(%d/%d)", currentPositionCount, maxPos));
        }
        if (availableMargin < openMargin * riskProps.getMinAvailableFactor()) {
            return RiskDecision.deny(String.format("可用保证金不足(%.2f < %.2f)", availableMargin, openMargin));
        }
        double estimatedUsed = currentPositionCount * openMargin;
        if (estimatedUsed + openMargin > maxTotalMargin) {
            return RiskDecision.deny(String.format("总保证金将超限(已用约%.2f + %.2f > %.2f)",
                    estimatedUsed, openMargin, maxTotalMargin));
        }
        if (isDailyLossBreached(user.getId(), dailyLossLimit)) {
            return RiskDecision.deny(String.format("触发单日亏损熔断(限额 %.2f U)", dailyLossLimit));
        }
        return RiskDecision.allow();
    }

    public void recordRealizedPnl(Long userId, BigDecimal pnl) {
        if (userId == null || pnl == null || redisTemplate == null) return;
        try {
            String key = dailyPnlKey(userId);
            redisTemplate.opsForValue().increment(key, pnl.doubleValue());
            Long ttl = redisTemplate.getExpire(key);
            if (ttl == null || ttl < 0) {
                redisTemplate.expire(key, Duration.ofHours(36));
            }
        } catch (Exception e) {
            log.warn("[risk] recordRealizedPnl failed: {}", e.getMessage());
        }
    }

    public boolean isDailyLossBreached(Long userId, double limit) {
        if (userId == null || limit <= 0 || redisTemplate == null) return false;
        try {
            String val = redisTemplate.opsForValue().get(dailyPnlKey(userId));
            if (val == null) return false;
            return Double.parseDouble(val) <= -Math.abs(limit);
        } catch (Exception e) {
            return false;
        }
    }

    public List<OpenPosition> findTimeoutPositions(Long userId) {
        int maxHold = riskProps.getMaxHoldMinutes();
        if (maxHold <= 0) return List.of();
        List<OpenPosition> open = openPositionMapper.findOpenByUserId(userId);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(maxHold);
        return open.stream()
                .filter(p -> p.getOpenedAt() != null && p.getOpenedAt().isBefore(deadline))
                .toList();
    }

    public RiskProperties getProps() {
        return riskProps;
    }

    private String dailyPnlKey(Long userId) {
        return "yaob:daily_pnl:" + userId + ":" + LocalDate.now();
    }
}
