package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.TradeHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TradeHistoryMapper extends BaseMapper<TradeHistory> {

    @Select("SELECT * FROM trade_history WHERE user_id = #{userId} ORDER BY opened_at DESC LIMIT #{limit}")
    List<TradeHistory> findByUserIdOrderByOpenedAtDesc(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM trade_history WHERE user_id = #{userId} ORDER BY opened_at DESC")
    List<TradeHistory> findAllByUserIdOrderByOpenedAtDesc(@Param("userId") Long userId);
}
