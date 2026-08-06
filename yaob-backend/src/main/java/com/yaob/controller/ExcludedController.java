package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.ExcludedSymbolService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExcludedController {

    @Autowired
    private ExcludedSymbolService excludedService;
    @Autowired
    private UserService userService;

    @GetMapping("/get_excluded_symbols_categorized")
    public Result<Map<String, Object>> getExcluded(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Object> data = excludedService.getExcluded(user.getId());
        return Result.success(data);
    }

    @PostMapping("/add_excluded_symbols")
    public Result<Map<String, Object>> addExcluded(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        List<String> symbols = (List<String>) body.get("symbols");
        String category = body.get("category") != null ? body.get("category").toString() : "manual";
        if (!"manual".equals(category) && !"large_cap".equals(category)) category = "manual";
        if (symbols == null) throw new BusinessException("symbols 不能为空");
        List<String> added = excludedService.addExcluded(user.getId(), symbols, category);
        return Result.success(Map.of("added", added));
    }

    @PostMapping("/remove_excluded_symbols")
    public Result<Void> removeExcluded(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        List<String> symbols = (List<String>) body.get("symbols");
        String category = body.get("category") != null ? body.get("category").toString() : null;
        if (symbols != null) {
            excludedService.removeExcluded(user.getId(), symbols, category);
        }
        return Result.success();
    }

    @PostMapping("/clear_excluded_symbols")
    public Result<Void> clearExcluded(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        excludedService.clearExcluded(user.getId());
        return Result.success();
    }

    @PostMapping("/restore_default_excluded")
    public Result<Map<String, Object>> restoreDefault(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        int count = excludedService.restoreDefault(user.getId());
        return Result.success(Map.of("count", count));
    }
}
