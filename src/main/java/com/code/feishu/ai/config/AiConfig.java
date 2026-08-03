package com.code.feishu.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 大模型配置。
 *
 * 在 application.properties 里以 ai.qwen. 开头配置：
 *   ai.qwen.api-key=sk-xxx
 *   ai.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
 *   ai.qwen.model=qwen-turbo
 *   ai.qwen.timeout=15000
 *   ai.qwen.temperature=0.1
 *
 * 后续如果换其他模型平台（如 DeepSeek、智谱），加一组配置即可。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.qwen")
public class AiConfig {

    /** API 密钥 */
    private String apiKey;

    /** API 基础地址（Qwen 兼容 OpenAI 格式） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 模型名称（qwen-turbo 最便宜，qwen-plus 更强） */
    private String model = "qwen-turbo";

    /** HTTP 超时时间（毫秒） */
    private int timeout = 15000;

    /** 温度（0-1，越低越确定，解析任务用 0.1） */
    private double temperature = 0.1;
}
