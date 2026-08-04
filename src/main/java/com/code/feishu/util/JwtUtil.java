package com.code.feishu.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 工具类。
 *
 * 职责：
 *   - generate(userId)  生成 JWT，subject = userId 字符串，有效期 jwt.expire-days 天
 *   - parseUserId(jwt)  解析 JWT 返回 userId，解析失败返回 null
 *
 * 密钥从 application.properties 的 jwt.secret 读取（HMAC-SHA256，至少 32 字节）。
 *
 * 注意：JWT 本身只是防篡改，真正的「是否仍然有效」由 Redis 里的存在性决定
 *      （AuthInterceptor 会查 Redis，登出时删 Redis key 即可使 token 失效）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-days:7}") long expireDays) {
        // HMAC-SHA256 要求密钥至少 32 字节
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofDays(expireDays);
    }

    /** 生成 JWT，subject = userId */
    public String generate(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration.toMillis());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 解析 JWT 返回 userId，失败返回 null */
    public Long parseUserId(String jwt) {
        if (jwt == null || jwt.isBlank()) return null;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            String subject = claims.getSubject();
            return subject == null ? null : Long.parseLong(subject);
        } catch (Exception e) {
            // 过期、签名错误、格式错误等都返回 null
            return null;
        }
    }
}
