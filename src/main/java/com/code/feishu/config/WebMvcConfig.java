package com.code.feishu.config;

import com.code.feishu.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册认证拦截器 + BCrypt 密码编码器 Bean。
 *
 * 拦截 /api/** 下除登录/健康检查/sms转发外的所有接口。
 * 静态资源（index.html 等）不在 /api/** 下，不受影响。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt 加密，strength=10（默认值，加密与验证都够用）
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/login",                // 登录接口
                        "/api/ping",                 // 健康检查
                        "/api/sms",                  // SmsForwarder 用 sms_key 认证，不走 JWT
                        "/api/register"              // 注册接口（已关闭，但预留路径避免被拦截）
                );
    }
}
