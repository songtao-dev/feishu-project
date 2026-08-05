package com.code.feishu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.config.RedisSafeTemplate;
import com.code.feishu.entity.User;
import com.code.feishu.mapper.UserMapper;
import com.code.feishu.util.JwtUtil;
import com.code.feishu.vo.LoginVO;
import com.code.feishu.vo.UserInfoVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户服务。
 *
 *   - login(username, password)  校验密码，生成 JWT 并存 Redis
 *   - logout(token)              删 Redis key 使 token 失效
 *   - findBySmsKey(smsKey)       SmsForwarder 调用 /api/sms 时按 sms_key 定位用户
 *   - getById(id)                查用户信息（脱敏）
 *
 * token 在 Redis 里的存储：
 *   key   = auth:token:<jwt>
 *   value = userId
 *   TTL   = jwt.expire-days 天
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Redis key 前缀（与 AuthInterceptor 保持一致） */
    public static final String REDIS_TOKEN_PREFIX = "auth:token:";

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisSafeTemplate redis;
    private final PasswordEncoder passwordEncoder;
    private final Duration tokenTtl;

    public UserService(UserMapper userMapper,
                       JwtUtil jwtUtil,
                       RedisSafeTemplate redis,
                       PasswordEncoder passwordEncoder,
                       @Value("${jwt.expire-days:7}") long expireDays) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
        this.tokenTtl = Duration.ofDays(expireDays);
    }

    /**
     * 登录。返回 LoginVO（含 token），失败返回 null。
     */
    public LoginVO login(String username, String password) {
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return null;
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            log.info("[Login] 用户不存在: {}", username);
            return null;
        }

        // BCrypt 校验
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.info("[Login] 密码错误: {}", username);
            return null;
        }

        // 生成 JWT
        String token = jwtUtil.generate(user.getId());

        // 存 Redis：auth:token:<jwt> → userId，TTL 7 天
        // RedisSafeTemplate.set() 内部已兜底所有连接异常：失败时返回 false，不阻断登录
        boolean stored = redis.set(REDIS_TOKEN_PREFIX + token, String.valueOf(user.getId()), tokenTtl);
        if (!stored) {
            log.warn("[Login] Redis 不可用（或写入失败），降级为纯 JWT 模式（不支持服务端登出失效）。");
        }

        log.info("[Login] 登录成功: userId={}, username={}, redis={}", user.getId(), username, redis.isAvailable() ? "ok" : "disabled");

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        return vo;
    }

    /**
     * 登出。删 Redis key 使 token 立即失效。
     * Redis 不可用时仅打日志（纯 JWT 下登出没意义，token 靠自身过期）。
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        boolean deleted = redis.delete(REDIS_TOKEN_PREFIX + token);
        log.info("[Logout] token={}..., redis={}, deleted={}",
                token.substring(0, Math.min(10, token.length())),
                redis.isAvailable() ? "ok" : "disabled",
                deleted);
    }

    /**
     * 按 sms_key 查用户。SmsForwarder 调用 /api/sms 时用此定位归属用户。
     * @return 找不到返回 null
     */
    public User findBySmsKey(String smsKey) {
        if (smsKey == null || smsKey.isBlank()) return null;
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getSmsKey, smsKey)
        );
    }

    /**
     * 按用户名查用户。共享日记本邀请成员时用。
     * @return 找不到返回 null
     */
    public User findByUsername(String username) {
        if (username == null || username.isBlank()) return null;
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username.trim())
        );
    }

    /**
     * 按 ID 查用户实体（含密码字段，仅内部使用，不要直接返回给前端）。
     */
    public User getByIdRaw(Long userId) {
        if (userId == null) return null;
        return userMapper.selectById(userId);
    }

    /**
     * 取用户昵称（优先 nickname，空则回退 username）。共享日记作者展示用。
     */
    public String getDisplayName(Long userId) {
        if (userId == null) return "";
        User u = userMapper.selectById(userId);
        if (u == null) return "";
        if (u.getNickname() != null && !u.getNickname().isBlank()) return u.getNickname();
        return u.getUsername() == null ? "" : u.getUsername();
    }

    /**
     * 查用户信息（脱敏，不含密码）。
     */
    public UserInfoVO getInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setSmsKey(user.getSmsKey());
        return vo;
    }
}
