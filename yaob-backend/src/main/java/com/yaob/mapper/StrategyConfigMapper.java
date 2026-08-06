package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.StrategyConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface StrategyConfigMapper extends BaseMapper<StrategyConfig> {

    @Select("SELECT * FROM strategy_configs WHERE user_id = #{userId}")
    List<StrategyConfig> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM strategy_configs WHERE user_id = #{userId} AND strategy = #{strategy}")
    StrategyConfig findByUserIdAndStrategy(@Param("userId") Long userId, @Param("strategy") String strategy);
}
