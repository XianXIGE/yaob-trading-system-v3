package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @TableField("password_hash")
    private String passwordHash;
    @TableField("is_vip")
    private Boolean isVip;
    @TableField("vip_expire_at")
    private LocalDateTime vipExpireAt;
    @TableField("is_admin")
    private Boolean isAdmin;
    @TableField("binance_api_key")
    private String binanceApiKey;
    @TableField("binance_api_secret")
    private String binanceApiSecret;
    @TableField("auto_trade_enabled")
    private Boolean autoTradeEnabled;
    private String marginMode;
    @TableField("position_mode")
    private String positionMode;
    @TableField("open_margin")
    private BigDecimal openMargin;
    private Integer leverage;
    @TableField("exclude_large_cap")
    private Boolean excludeLargeCap;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
