package com.yaob.service;

import com.yaob.dto.AdminUserVO;
import com.yaob.entity.User;
import com.yaob.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AdminService {

    @Autowired
    private UserMapper userMapper;

    @Value("${admin.user}")
    private String adminUser;

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
}
