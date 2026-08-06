package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.entity.User;
import com.yaob.service.AdminService;
import com.yaob.service.UserService;
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

    @GetMapping("/users")
    public Result<Map<String, Object>> listUsers(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        if (!userService.isAdmin(user)) throw new BusinessException(403, "无管理权限");
        var users = adminService.listUsers();
        return Result.success(Map.of("users", users));
    }

    @PostMapping("/set_vip")
    public Result<Map<String, Object>> setVip(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        if (!userService.isAdmin(user)) throw new BusinessException(403, "无管理权限");

        String username = (String) body.get("username");
        boolean vip = Boolean.TRUE.equals(body.get("vip"));
        int days = body.get("days") != null ? Integer.parseInt(body.get("days").toString()) : 0;

        adminService.setVip(username, vip, days);
        return Result.success(Map.of("username", username, "vip", vip));
    }

    @PostMapping("/delete_user")
    public Result<Map<String, Object>> deleteUser(@RequestBody Map<String, String> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        if (!userService.isAdmin(user)) throw new BusinessException(403, "无管理权限");

        String username = body.get("username");
        adminService.deleteUser(username);
        return Result.success(Map.of("username", username));
    }
}
