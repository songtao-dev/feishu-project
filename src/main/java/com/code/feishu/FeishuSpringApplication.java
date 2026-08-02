package com.code.feishu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.code.feishu.mapper")
@EnableScheduling
public class FeishuSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeishuSpringApplication.class, args);
    }
}
