package com.yaob.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "crypto")
public class CryptoProperties {
    private String secret = "yaob-change-me-in-production-2026";
}
