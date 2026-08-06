package com.yaob;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.yaob.mapper")
@EnableScheduling
public class YaobApplication {
    public static void main(String[] args) {
        SpringApplication.run(YaobApplication.class, args);
    }
}
