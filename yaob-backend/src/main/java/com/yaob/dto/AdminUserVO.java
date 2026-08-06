package com.yaob.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private Boolean isVip;
    private LocalDateTime vipExpireAt;
    private Boolean isAdmin;
    private LocalDateTime createdAt;
}
