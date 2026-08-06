package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.PositionService;
import com.yaob.service.StatsService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/trade_profit_stats")
    public Result<Map<String, Object>> tradeProfitStats(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Object> stats = positionService.getTradeProfitStats(user.getId());
        return Result.success(stats);
    }
}
