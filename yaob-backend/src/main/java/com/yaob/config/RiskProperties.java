package com.yaob.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskProperties {
    private int maxPositions = 3;
    private double maxTotalMargin = 50.0;
    private double dailyLossLimit = 30.0;
    private double maxOpenMargin = 20.0;
    private int maxLeverage = 10;
    private int maxHoldMinutes = 1440;
    private double minAvailableFactor = 1.0;
}
