package com.code.feishu.context;

/**
 * 用户上下文（ThreadLocal 存 userId）。
 *
 * AuthInterceptor 校验 token 通过后，把 userId 放进 ThreadLocal，
 * Controller / Service 里通过 UserContext.getUserId() 取当前登录用户 ID，
 * 用于数据隔离过滤。
 *
 * 请求结束在 afterCompletion 里 clear()，防止线程池线程复用导致 userId 串号。
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
