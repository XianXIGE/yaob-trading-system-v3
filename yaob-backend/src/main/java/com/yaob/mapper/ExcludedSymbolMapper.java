package com.yaob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yaob.entity.ExcludedSymbol;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExcludedSymbolMapper extends BaseMapper<ExcludedSymbol> {

    @Select("SELECT * FROM excluded_symbols WHERE user_id = #{userId}")
    List<ExcludedSymbol> findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM excluded_symbols WHERE user_id = #{userId} AND category = #{category}")
    List<ExcludedSymbol> findByUserIdAndCategory(@Param("userId") Long userId, @Param("category") String category);
}
