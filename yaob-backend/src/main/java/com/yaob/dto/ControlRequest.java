package com.yaob.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ControlRequest {
    private BigDecimal openMargin;
    private Integer leverage;
}
