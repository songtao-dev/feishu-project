package com.code.feishu.interceptor;

import com.code.feishu.config.RedisSafeTemplate;
import com.code.feishu.context.UserContext;
import com.code.feishu.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 认证拦截器。
 *
 * 流程：
 *   1. 从 Authorization 头取 Bearer <token>
 *   2. 用 JwtUtil 解析出 userId
 *   3. 查 Redis auth:token:<token> 是否存在且值 == userId
 *   4. 通过 → UserContext.setUserId(userId)，return true
 *   5. 失败 → 返回 401 JSON
 *
 * Redis 降级策略（用 RedisSafeTemplate 兜底）：
 *   - Redis 可用 → 正常查 Redis，token 登出后即失效。
 *   - Redis 不可用 / 启动时没连上 → 降级为纯 JWT 校验：只要 JWT 本身合法就放行。
 *     这种模式下"登出"不生效，但业务完全可用；等 Redis 恢复，自动回到完整模式。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Redis key 前缀：auth:token:<jwt> → userId */
    public static final String REDIS_TOKEN_PREFIX = "auth:token:";

    private final JwtUtil jwtUtil;
    private final RedisSafeTemplate redis;

    public AuthInterceptor(JwtUtil jwtUtil, RedisSafeTemplate redis) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeUnauthorized(response, "未登录");
        }
        String token = authHeader.substring(7).trim();

        // 1. 解析 JWT（先解析，JWT 无效直接 401，不依赖 Redis）
        Long userId = jwtUtil.parseUserId(token);
        if (userId == null) {
            return writeUnauthorized(response, "token 无效");
        }

        // 2. 查 Redis：token 是否仍然有效（登出时会被删除）
        //    RedisSafeTemplate.get() 内部已兜底了所有连接异常，直接判空即可。
        String redisKey = REDIS_TOKEN_PREFIX + token;
        String storedUserId = redis.get(redisKey);
        // Redis 没值：
        //   a) Redis 连不上（此时 RedisSafeTemplate 已经打了 warn 日志）→ 降级为纯 JWT 放行
        //   b) Redis 连上了但 token 不存在 → 视为已失效（被登出了），返回 401
        // 怎么区分？用 isAvailable() 判断：
        if (!redis.isAvailable()) {
            // Redis bean 本身没注入（启动时没连上等）→ 降级模式，放行
            UserContext.setUserId(userId);
            return true;
        }
        if (storedUserId == null) {
            // Redis 连上了但没有这个 key → 确实失效了
            return writeUnauthorized(response, "token 已失效，请重新登录");
        }
        if (!String.valueOf(userId).equals(storedUserId)) {
            return writeUnauthorized(response, "token 不匹配");
        }

        // 3. 通过，放入 ThreadLocal
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 防止线程池线程复用导致 userId 串号
        UserContext.clear();
    }

    private boolean writeUnauthorized(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"code\":401,\"msg\":\"" + msg + "\"}");
        return false;
    }
}
