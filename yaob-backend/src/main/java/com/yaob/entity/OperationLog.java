package com.yaob.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operations_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("operator_id")
    private Long operatorId;
    private String operator;
    private String action;
    @TableField("target_username")
    private String targetUsername;
    private String detail;
    private String ip;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
