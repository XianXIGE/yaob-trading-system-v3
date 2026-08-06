package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);
}
