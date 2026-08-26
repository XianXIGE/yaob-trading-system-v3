package com.yaob.service;

import com.yaob.config.CryptoUtil;
import com.yaob.dto.AdminUserVO;
import com.yaob.entity.*;
import com.yaob.mapper.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AdminService {

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
    private OperationLogMapper operationLogMapper;
    @Autowired
    private BinanceFapiService binanceFapiService;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Value("${admin.user}")
    private String adminUser;

    // ============================================================
    // 原有：用户列表账号管理
    // ============================================================
    public List<AdminUserVO> listUsers() {
        List<User> users = userMapper.selectList(null);
        List<AdminUserVO> result = new ArrayList<>();
        for (User u : users) {
            AdminUserVO vo = new AdminUserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setIsVip(u.getIsVip());
            vo.setVipExpireAt(u.getVipExpireAt());
            vo.setIsAdmin(u.getIsAdmin());
            vo.setCreatedAt(u.getCreatedAt());
            result.add(vo);
        }
        return result;
    }

    public void setVip(String username, boolean vip, int days) {
        User user = userMapper.findByUsername(username);
        if (user == null) throw new RuntimeException("账号 " + username + " 不存在");
        if (username.equals(adminUser)) throw new RuntimeException("不能修改管理员自身 VIP");
        user.setIsVip(vip);
        if (vip) {
            user.setVipExpireAt(days > 0 ? java.time.LocalDateTime.now().plusDays(days) : null);
        } else {
            user.setVipExpireAt(null);
        }
        userMapper.updateById(user);
    }

    public void deleteUser(String username) {
        User user = userMapper.findByUsername(username);
        if (user == null) throw new RuntimeException("账号 " + username + " 不存在");
        if (username.equals(adminUser)) throw new RuntimeException("不能删除管理员账号");
        userMapper.deleteById(user.getId());
    }

    /** [v3.6] 管理员设置/解除用户的单日亏损熔断 (circuit_breaker_override) */
    public void setCircuitBreakerOverride(Long userId, boolean override) {
        User user = requireUser(userId);
        user.setCircuitBreakerOverride(override);
        userMapper.updateById(user);
    }

    // ============================================================
    // C 功能：用户详情查询（管理员）
    // ============================================================

    private User requireUser(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) throw new RuntimeException("用户不存在 id=" + userId);
        return u;
    }

    /** 账户概览 + 资产汇总 */
    public Map<String, Object> getOverview(Long userId) {
        User u = requireUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("is_vip", u.getIsVip());
        m.put("vip_expiry", u.getVipExpireAt());
        m.put("is_admin", u.getIsAdmin());
        m.put("auto_trade_enabled", u.getAutoTradeEnabled());
        m.put("margin_mode", u.getMarginMode());
        m.put("position_mode", u.getPositionMode());
        m.put("open_margin", u.getOpenMargin());
        m.put("leverage", u.getLeverage());
        m.put("exclude_large_cap", u.getExcludeLargeCap());
        m.put("circuit_breaker_override", u.getCircuitBreakerOverride());
        m.put("created_at", u.getCreatedAt());

        // 累计已实现盈亏（trade_history）
        BigDecimal realizedPnl = realizedPnl(userId);

        // 资产：优先币安实时，异常/未绑定则用 DB 估算
        Map<String, Object> asset = new LinkedHashMap<>();
        boolean realtime = false;
        try {
            String key = cryptoUtil.decrypt(u.getBinanceApiKey());
            String secret = cryptoUtil.decrypt(u.getBinanceApiSecret());
            if (key != null && !key.isBlank() && secret != null && !secret.isBlank()) {
                JsonNode acc = binanceFapiService.account(key, secret);
                asset.put("wallet_balance", n(acc, "totalWalletBalance"));
                asset.put("available_balance", n(acc, "availableBalance"));
                asset.put("unrealized_profit", n(acc, "totalUnrealizedProfit"));
                asset.put("total_equity", n(acc, "totalWalletBalance") + n(acc, "totalUnrealizedProfit"));
                asset.put("source", "binance_realtime");
                realtime = true;
            }
        } catch (Exception e) {
            log.warn("查询用户 {} 币安余额失败: {}", userId, e.getMessage());
        }
        if (!realtime) {
            // DB 估算：open_margin + trade_history 累计盈亏
            asset.put("wallet_balance", u.getOpenMargin() == null ? BigDecimal.ZERO : u.getOpenMargin());
            asset.put("available_balance", null);
            asset.put("unrealized_profit", null);
            asset.put("total_equity", (u.getOpenMargin() == null ? BigDecimal.ZERO : u.getOpenMargin()).add(realizedPnl));
            asset.put("source", "db_estimated");
        }
        m.put("asset", asset);
        m.put("realized_pnl", realizedPnl);
        m.put("open_position_count", openPositionMapper.findOpenByUserId(userId).size());
        m.put("total_trade_count", tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId).size());
        return m;
    }

    /** 当前持仓 */
    public List<Map<String, Object>> getPositions(Long userId) {
        requireUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OpenPosition p : openPositionMapper.findOpenByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("symbol", p.getSymbol());
            row.put("strategy", p.getStrategy());
            row.put("direction", p.getDirection());
            row.put("qty", p.getQty());
            row.put("entry_price", p.getEntryPrice());
            row.put("leverage", p.getLeverage());
            row.put("tp_ratio", p.getTpRatio());
            row.put("sl_ratio", p.getSlRatio());
            row.put("status", p.getStatus());
            row.put("opened_at", p.getOpenedAt());
            // 浮盈亏估算：方向多空 * qty * (现价-开仓) —— 无实时现价，用开仓价近似为0，标注来源
            out.add(row);
        }
        return out;
    }

    /** 历史交易 */
    public List<Map<String, Object>> getTrades(Long userId) {
        requireUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TradeHistory t : tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("symbol", t.getSymbol());
            row.put("strategy", t.getStrategy());
            row.put("direction", t.getDirection());
            row.put("qty", t.getQty());
            row.put("entry_price", t.getEntryPrice());
            row.put("exit_price", t.getExitPrice());
            row.put("leverage", t.getLeverage());
            row.put("pnl", t.getPnl());
            row.put("pnl_ratio", t.getPnlRatio());
            row.put("close_reason", t.getCloseReason());
            row.put("opened_at", t.getOpenedAt());
            row.put("closed_at", t.getClosedAt());
            out.add(row);
        }
        return out;
    }

    /** 策略配置 + 统计 */
    public Map<String, Object> getStrategies(Long userId) {
        User u = requireUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> cfgs = new ArrayList<>();
        for (StrategyConfig c : strategyConfigMapper.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", c.getStrategy());
            row.put("enabled", c.getEnabled());
            row.put("tp_ratio", c.getTpRatio());
            row.put("sl_ratio", c.getSlRatio());
            row.put("params", c.getParamsJson());
            row.put("strategy_type", c.getStrategyType());
            row.put("description", c.getDescription());
            cfgs.add(row);
        }
        m.put("strategies", cfgs);
        List<Map<String, Object>> stats = new ArrayList<>();
        for (StrategyStat s : strategyStatMapper.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", s.getStrategy());
            row.put("total_trades", s.getTotalTrades());
            row.put("win_trades", s.getWinTrades());
            row.put("total_pnl", s.getTotalPnl());
            row.put("tp_count", s.getTpCount());
            row.put("sl_count", s.getSlCount());
            row.put("manual_count", s.getManualCount());
            stats.add(row);
        }
        m.put("stats", stats);
        return m;
    }

    /** 黑名单/风控 */
    public Map<String, Object> getExcluded(Long userId) {
        requireUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExcludedSymbol e : excludedSymbolMapper.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", e.getSymbol());
            row.put("category", e.getCategory());
            row.put("created_at", e.getCreatedAt());
            list.add(row);
        }
        m.put("excluded_symbols", list);
        m.put("count", list.size());
        return m;
    }

    /** 操作日志（管理后台操作，全部管理员可见；可选按用户过滤） */
    public List<Map<String, Object>> getLogs(Long targetUserId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<OperationLog> logs;
        if (targetUserId != null) {
            User target = userMapper.selectById(targetUserId);
            String username = target != null ? target.getUsername() : null;
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog> q =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            if (username != null) q.eq(OperationLog::getTargetUsername, username);
            q.orderByDesc(OperationLog::getCreatedAt).last("LIMIT " + Math.min(limit <= 0 ? 200 : limit, 500));
            logs = operationLogMapper.selectList(q);
        } else {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog> q =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            q.orderByDesc(OperationLog::getCreatedAt).last("LIMIT " + Math.min(limit <= 0 ? 200 : limit, 500));
            logs = operationLogMapper.selectList(q);
        }
        for (OperationLog o : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.getId());
            row.put("operator", o.getOperator());
            row.put("action", o.getAction());
            row.put("target_username", o.getTargetUsername());
            row.put("detail", o.getDetail());
            row.put("ip", o.getIp());
            row.put("created_at", o.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** 记录管理操作日志（operator 需为管理员，由 Controller 传入） */
    public void logOperation(String operator, String action, String targetUsername, String detail, String ip) {
        try {
            OperationLog o = new OperationLog();
            User op = userMapper.findByUsername(operator);
            o.setOperatorId(op != null ? op.getId() : 0L);
            o.setOperator(operator);
            o.setAction(action);
            o.setTargetUsername(targetUsername);
            o.setDetail(detail != null && detail.length() > 512 ? detail.substring(0, 512) : detail);
            o.setIp(ip);
            operationLogMapper.insert(o);
        } catch (Exception e) {
            log.error("记录操作日志失败: {}", e.getMessage());
        }
    }

    // ============================================================
    // 辅助
    // ============================================================
    private BigDecimal realizedPnl(Long userId) {
        try {
            List<TradeHistory> all = tradeHistoryMapper.findAllByUserIdOrderByOpenedAtDesc(userId);
            BigDecimal sum = BigDecimal.ZERO;
            for (TradeHistory t : all) {
                if (t.getPnl() != null) sum = sum.add(t.getPnl());
            }
            return sum.setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private double n(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || v.isNull()) ? 0 : v.asDouble();
    }
}
