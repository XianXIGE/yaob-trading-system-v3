package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.StrategyStat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface StrategyStatMapper extends BaseMapper<StrategyStat> {

    @Select("SELECT * FROM strategy_stats WHERE user_id = #{userId}")
    List<StrategyStat> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM strategy_stats WHERE user_id = #{userId} AND strategy = #{strategy}")
    StrategyStat findByUserIdAndStrategy(@Param("userId") Long userId, @Param("strategy") String strategy);
}
