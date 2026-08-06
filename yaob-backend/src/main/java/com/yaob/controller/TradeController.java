package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.dto.ControlRequest;
import com.yaob.entity.User;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TradeController {

    @Autowired
    private UserService userService;

    @PostMapping("/control")
    public Result<Void> control(@RequestBody ControlRequest req, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        userService.updateControl(user.getId(), req.getOpenMargin(), req.getLeverage());
        return Result.success();
    }

    @PostMapping("/set_api_keys")
    public Result<Void> setApiKeys(@RequestBody Map<String, String> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        String apiKey = body.get("api_key");
        String apiSecret = body.get("api_secret");
        userService.setApiKeys(user.getId(), apiKey, apiSecret);
        return Result.<Void>success("保存成功", null);
    }

    @PostMapping("/clear_api_keys")
    public Result<Void> clearApiKeys(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        userService.clearApiKeys(user.getId());
        return Result.<Void>success("已清除", null);
    }

    @PostMapping("/toggle_auto_trade")
    public Result<Map<String, Object>> toggleAutoTrade(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        userService.toggleAutoTrade(user.getId());
        user = userService.findById(user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auto_trade_enabled", user.getAutoTradeEnabled());
        return Result.success(data);
    }

    @PostMapping("/toggle_margin_mode")
    public Result<Map<String, Object>> toggleMarginMode(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        userService.toggleMarginMode(user.getId());
        user = userService.findById(user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("margin_mode", user.getMarginMode());
        return Result.success(data);
    }

    @PostMapping("/toggle_exclude_large_cap")
    public Result<Map<String, Object>> toggleExcludeLargeCap(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        userService.checkVip(user);
        userService.toggleExcludeLargeCap(user.getId());
        user = userService.findById(user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exclude_large_cap", user.getExcludeLargeCap());
        return Result.success(data);
    }
}
