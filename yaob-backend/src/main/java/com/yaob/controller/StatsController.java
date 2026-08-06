package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.StatsService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class StatsController {

    @Autowired
    private StatsService statsService;
    @Autowired
    private UserService userService;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        Map<String, Object> data = statsService.getStats(user.getId());
        return Result.success(data);
    }

    @PostMapping("/reset_stats")
    public Result<Void> resetStats(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        statsService.resetStats(user.getId());
        return Result.success();
    }

    @PostMapping("/test_alert")
    public Result<Void> testAlert(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        log.info("[alert] 告警测试 from {} at {}", user.getUsername(), LocalDateTime.now());
        return Result.success();
    }
}
