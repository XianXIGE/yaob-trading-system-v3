package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.AdminService;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;
    @Autowired
    private UserService userService;

    // ---------- 权限辅助 ----------
    private User requireAdmin(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        if (!userService.isAdmin(user)) throw new BusinessException(403, "无管理权限");
        return user;
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }

    // ---------- 用户列表 ----------
    @GetMapping("/users")
    public Result<Map<String, Object>> listUsers(HttpSession session) {
        requireAdmin(session);
        var users = adminService.listUsers();
        return Result.success(Map.of("users", users));
    }

    // ---------- VIP / 删除 ----------
    @PostMapping("/set_vip")
    public Result<Map<String, Object>> setVip(@RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        User admin = requireAdmin(session);
        String username = (String) body.get("username");
        boolean vip = Boolean.TRUE.equals(body.get("vip"));
        int days = body.get("days") != null ? Integer.parseInt(body.get("days").toString()) : 0;
        adminService.setVip(username, vip, days);
        adminService.logOperation(admin.getUsername(), vip ? "授权VIP" : "撤销VIP", username,
                vip ? ("授权VIP，" + (days > 0 ? days + "天" : "永久")) : "撤销VIP", clientIp(request));
        return Result.success(Map.of("username", username, "vip", vip));
    }

    @PostMapping("/delete_user")
    public Result<Map<String, Object>> deleteUser(@RequestBody Map<String, String> body, HttpSession session, HttpServletRequest request) {
        User admin = requireAdmin(session);
        String username = body.get("username");
        adminService.deleteUser(username);
        adminService.logOperation(admin.getUsername(), "删除用户", username, "删除用户账号", clientIp(request));
        return Result.success(Map.of("username", username));
    }

    // ---------- [v3.6] 熔断解除开关 (admin only) ----------
    @PostMapping("/users/{userId}/circuit_breaker_override")
    public Result<Map<String, Object>> setCircuitBreakerOverride(@PathVariable Long userId,
                                                                 @RequestBody Map<String, Boolean> body,
                                                                 HttpSession session,
                                                                 HttpServletRequest request) {
        User admin = requireAdmin(session);
        boolean override = Boolean.TRUE.equals(body.get("override"));
        adminService.setCircuitBreakerOverride(userId, override);
        adminService.logOperation(admin.getUsername(), override ? "解除熔断" : "恢复熔断",
                String.valueOf(userId), override ? "手动解除单日亏损熔断，恢复交易" : "恢复单日亏损熔断保护", clientIp(request));
        return Result.success(Map.of("userId", userId, "circuit_breaker_override", override));
    }

    // ---------- C 功能：用户详情查询（admin only） ----------
    @GetMapping("/users/{userId}/overview")
    public Result<Map<String, Object>> overview(@PathVariable Long userId, HttpSession session, HttpServletRequest request) {
        User admin = requireAdmin(session);
        Map<String, Object> data = adminService.getOverview(userId);
        adminService.logOperation(admin.getUsername(), "查看用户详情", String.valueOf(data.get("username")), "查看账户概览", clientIp(request));
        return Result.success(data);
    }

    @GetMapping("/users/{userId}/positions")
    public Result<List<Map<String, Object>>> positions(@PathVariable Long userId, HttpSession session) {
        requireAdmin(session);
        return Result.success(adminService.getPositions(userId));
    }

    @GetMapping("/users/{userId}/trades")
    public Result<List<Map<String, Object>>> trades(@PathVariable Long userId, HttpSession session) {
        requireAdmin(session);
        return Result.success(adminService.getTrades(userId));
    }

    @GetMapping("/users/{userId}/strategies")
    public Result<Map<String, Object>> strategies(@PathVariable Long userId, HttpSession session) {
        requireAdmin(session);
        return Result.success(adminService.getStrategies(userId));
    }

    @GetMapping("/users/{userId}/excluded")
    public Result<Map<String, Object>> excluded(@PathVariable Long userId, HttpSession session) {
        requireAdmin(session);
        return Result.success(adminService.getExcluded(userId));
    }

    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(@RequestParam(required = false) Long userId,
                                                  @RequestParam(defaultValue = "200") int limit,
                                                  HttpSession session) {
        requireAdmin(session);
        return Result.success(adminService.getLogs(userId, limit));
    }
}
