package com.code.feishu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 安全的 Redis 模板封装。
 *
 * 为什么需要它：
 *   1. Spring Boot 的 StringRedisTemplate 在 Redis 连不上时，执行任何 ops 都会抛异常。
 *   2. 我们希望：Redis 只是"增强功能"（支持登出使 token 失效），不是核心能力。
 *      即便 Redis 挂了、没装、地址配错了，应用也必须能启动 + 能登录（降级为纯 JWT 模式）。
 *   3. 这里把所有可能抛连接异常的方法包一层 try/catch，失败时返回"合理的降级值"并打日志。
 *
 * 使用方式：
 *   把原来注入 StringRedisTemplate 的地方，改成注入 RedisSafeTemplate。
 *   AuthInterceptor / UserService 都已经改了。
 */
@Component
public class RedisSafeTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisSafeTemplate.class);

    /**
     * 用 @Autowired(required = false)：
     *   即使 Redis 自动配置在启动期因为各种原因没创建出 StringRedisTemplate，
     *   也只是 redis = null，不会把整个应用启动搞挂。
     */
    @Autowired(required = false)
    private StringRedisTemplate template;

    /** 读一个 key。找不到返回 null；Redis 不可用也返回 null。*/
    public String get(String key) {
        if (template == null) return null;
        try {
            return template.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[Redis] get({}) 失败，降级为 null：{}", key, e.getMessage());
            return null;
        }
    }

    /** 写 key=value，带过期时间。失败返回 false。 */
    public boolean set(String key, String value, Duration ttl) {
        if (template == null) return false;
        try {
            template.opsForValue().set(key, value, ttl);
            return true;
        } catch (Exception e) {
            log.warn("[Redis] set({}) 失败：{}", key, e.getMessage());
            return false;
        }
    }

    /** 删一个 key。失败返回 false，成功返回 true。 */
    public boolean delete(String key) {
        if (template == null) return false;
        try {
            Boolean del = template.delete(key);
            return Boolean.TRUE.equals(del);
        } catch (Exception e) {
            log.warn("[Redis] delete({}) 失败：{}", key, e.getMessage());
            return false;
        }
    }

    /** Redis 是否可用（连接上了 + set 了模板 bean）。只用于日志判断，别拿它做业务分支。 */
    public boolean isAvailable() {
        return template != null;
    }
}
