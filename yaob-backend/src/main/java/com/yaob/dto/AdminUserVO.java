package com.yaob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    @JsonProperty("is_vip")
    private Boolean isVip;
    @JsonProperty("vip_expiry")
    private LocalDateTime vipExpireAt;
    @JsonProperty("is_admin")
    private Boolean isAdmin;
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
