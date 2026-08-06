package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.dto.ToggleStrategyRequest;
import com.yaob.entity.User;
import com.yaob.service.StrategyService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StrategyController {

    @Autowired
    private StrategyService strategyService;
    @Autowired
    private UserService userService;

    @GetMapping("/get_strategy_params")
    public Result<Map<String, Map<String, Object>>> getStrategyParams(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Map<String, Object>> params = strategyService.getStrategyParams(user.getId());
        return Result.success(params);
    }

    @PostMapping("/save_strategy_params")
    public Result<Void> saveStrategyParams(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>) body.get("strategy_params");
        if (params == null) throw new BusinessException("参数格式错误");
        strategyService.saveStrategyParams(user.getId(), params);
        return Result.success();
    }

    @PostMapping("/toggle_strategy")
    public Result<Map<String, Object>> toggleStrategy(@RequestBody ToggleStrategyRequest req, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        String strategy = req.getStrategy();
        strategyService.toggleStrategy(user.getId(), strategy);
        Map<String, Boolean> states = strategyService.getStrategyStates(user.getId());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("strategy", strategy.toUpperCase());
        data.put("enabled", states.get(strategy.toUpperCase()));
        return Result.success(data);
    }

    @PostMapping("/add_strategy")
    public Result<Void> addStrategy(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        String strategy = (String) body.get("strategy");
        if (strategy == null || strategy.isBlank()) throw new BusinessException("策略不能为空");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        String type = (String) body.get("type");
        String description = (String) body.get("description");
        strategyService.addStrategy(user.getId(), strategy, params, type, description);
        return Result.success("策略已添加", null);
    }

    @DeleteMapping("/delete_strategy")
    public Result<Void> deleteStrategy(@RequestParam String strategy, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        strategyService.deleteStrategy(user.getId(), strategy.toUpperCase());
        return Result.success("策略已删除", null);
    }
}
