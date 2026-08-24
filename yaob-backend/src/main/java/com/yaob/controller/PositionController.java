package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.PositionService;
import com.yaob.service.StatsService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j

@RestController
@RequestMapping("/api")
public class PositionController {

    @Autowired
    private PositionService positionService;
    @Autowired
    private StatsService statsService;
    @Autowired
    private UserService userService;

    @GetMapping("/trade_history")
    public Result<Map<String, Object>> tradeHistory(
            @RequestParam(defaultValue = "200") int limit,
            HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        var trades = positionService.getTradeHistory(user.getId(), limit);
        Map<String, Object> data = Map.of("trades", trades, "total", trades.size());
        return Result.success(data);
    }

    @GetMapping("/strategy_stats")
    public Result<Map<String, Object>> strategyStats(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        var stats = statsService.getStrategyStats(user.getId());
        return Result.success(Map.of("strategy_stats", stats));
    }

    @PostMapping("/close_position")
    public Result<String> closePosition(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");

        Object symbolObj = body.get("symbol");
        if (symbolObj == null || symbolObj.toString().isBlank()) {
            throw new BusinessException("缺少交易对参数 symbol");
        }
        String symbol = symbolObj.toString().trim().toUpperCase();
        log.info("[close_position] userId={} 请求平仓 symbol=({}) len={}", user.getId(), symbol, symbol.length());

        positionService.closePositionBySymbol(user.getId(), symbol);
        return Result.success(symbol + " 平仓成功");
    }

    @GetMapping("/trade_profit_stats")
    public Result<Map<String, Object>> tradeProfitStats(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Object> stats = positionService.getTradeProfitStats(user.getId());
        return Result.success(stats);
    }
}
