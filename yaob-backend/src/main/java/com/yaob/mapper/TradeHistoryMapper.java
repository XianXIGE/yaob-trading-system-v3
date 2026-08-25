package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.TradeHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface TradeHistoryMapper extends BaseMapper<TradeHistory> {

    @Select("SELECT * FROM trade_history WHERE user_id = #{userId} ORDER BY opened_at DESC LIMIT #{limit}")
    List<TradeHistory> findByUserIdOrderByOpenedAtDesc(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM trade_history WHERE user_id = #{userId} ORDER BY opened_at DESC")
    List<TradeHistory> findAllByUserIdOrderByOpenedAtDesc(@Param("userId") Long userId);

    /**
     * 当日（从 from 时刻起）已实现盈亏合计。
     * 直接由数据库聚合 closed_at >= from 的 pnl，避免全表拉回内存过滤。
     * 无匹配记录时返回 null。
     */
    @Select("SELECT SUM(pnl) FROM trade_history WHERE user_id = #{userId} AND closed_at >= #{from} AND pnl IS NOT NULL")
    BigDecimal sumRealizedPnlSince(@Param("userId") Long userId, @Param("from") LocalDateTime from);
}
