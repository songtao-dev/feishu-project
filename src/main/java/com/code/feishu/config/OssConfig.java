package com.code.feishu.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端配置。
 *
 * 读取 application.properties 里的 oss.* 配置，创建 OSSClient Bean。
 * 应用关闭时通过 @PreDestroy 主动关闭连接池。
 */
@Configuration
public class OssConfig {

    @Value("${oss.access-key-id}")
    private String accessKeyId;

    @Value("${oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${oss.endpoint}")
    private String endpoint;

    private OSS ossClient;

    @Bean
    public OSS ossClient() {
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        return ossClient;
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }
}
