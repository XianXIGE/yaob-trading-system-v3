package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.OpenPosition;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface OpenPositionMapper extends BaseMapper<OpenPosition> {

    @Select("SELECT * FROM open_positions WHERE user_id = #{userId} AND status = 'OPEN' ORDER BY opened_at DESC")
    List<OpenPosition> findOpenByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM open_positions WHERE user_id = #{userId} AND symbol = #{symbol} AND status = 'OPEN'")
    OpenPosition findOpenByUserIdAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);
}
