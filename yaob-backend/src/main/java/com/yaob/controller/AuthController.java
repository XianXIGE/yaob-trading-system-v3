package com.yaob.controller;

import com.yaob.common.BusinessException;
import com.yaob.common.Result;
import com.yaob.dto.LoginRequest;
import com.yaob.dto.RegisterRequest;
import com.yaob.entity.User;
import com.yaob.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req, HttpSession session) {
        User user = userService.login(req.getUsername(), req.getPassword(), session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("is_vip", userService.isVip(user));
        data.put("is_admin", userService.isAdmin(user));
        return Result.success(data);
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody RegisterRequest req, HttpSession session) {
        User user = userService.register(req);
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("is_vip", false);
        data.put("is_admin", userService.isAdmin(user));
        return Result.success(data);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpSession session) {
        session.invalidate();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) {
            throw new BusinessException(401, "未登录");
        }
        userService.checkVipExpiry(user);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("is_vip", userService.isVip(user));
        data.put("is_admin", userService.isAdmin(user));
        data.put("vip_expire_at", user.getVipExpireAt());
        return Result.success(data);
    }

    @PostMapping("/change_password")
    public Result<Void> changePassword(@RequestBody Map<String, String> body, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) throw new BusinessException(401, "未登录");
        String oldPassword = body.get("old_password");
        String newPassword = body.get("new_password");
        if (oldPassword == null || oldPassword.isBlank())
            throw new BusinessException("请输入旧密码");
        if (newPassword == null || newPassword.length() < 6)
            throw new BusinessException("新密码至少6位");
        userService.changePassword(user.getId(), oldPassword, newPassword);
        return Result.success("密码修改成功", null);
    }
}
