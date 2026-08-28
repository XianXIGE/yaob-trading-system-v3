package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.MarketData;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 行情落库 Mapper（P0-1）。
 * 批量写入走 BaseMapper, 回测/ATR 读取按 symbol+interval 时间范围查。
 */
public interface MarketDataMapper extends BaseMapper<MarketData> {

    /**
     * 拉取某标的某周期的时间范围K线（按开盘时间升序）。
     */
    @Select("SELECT id,symbol,`interval`,open_time,`open`,high,low,close,volume,quote_volume,created_at " +
            "FROM market_data WHERE symbol=#{symbol} AND `interval`=#{interval} " +
            "AND open_time>=#{startTs} AND open_time<=#{endTs} ORDER BY open_time ASC")
    List<MarketData> queryRange(@Param("symbol") String symbol,
                                @Param("interval") String interval,
                                @Param("startTs") long startTs,
                                @Param("endTs") long endTs);

    /**
     * 某周期最新一条落库K线的时间戳（用于增量拉取，避免重复）。
     */
    @Select("SELECT COALESCE(MAX(open_time),0) FROM market_data WHERE symbol=#{symbol} AND `interval`=#{interval}")
    long maxOpenTime(@Param("symbol") String symbol, @Param("interval") String interval);
}
