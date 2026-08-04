package com.code.feishu.controller;

import com.code.feishu.context.UserContext;
import com.code.feishu.dto.LoginDTO;
import com.code.feishu.entity.User;
import com.code.feishu.mapper.UserMapper;
import com.code.feishu.service.UserService;
import com.code.feishu.vo.LoginVO;
import com.code.feishu.vo.UserInfoVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户相关接口。
 *
 *   POST /api/login        登录，返回 JWT token
 *   POST /api/logout       登出，删 Redis 使 token 失效
 *   GET  /api/user/info    查当前登录用户信息（含 sms_key，用于配置 SmsForwarder）
 *
 * 注册功能已关闭（账号由管理员在数据库手动创建），无 /api/register 接口。
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService, UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录。
     * 请求：{"username":"admin","password":"admin123"}
     * 成功：{"ok":true,"token":"xxx","userId":1,"username":"admin","nickname":"管理员"}
     * 失败：{"ok":false,"msg":"用户名或密码错误"}
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginDTO dto) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (dto == null || dto.getUsername() == null || dto.getPassword() == null) {
            resp.put("ok", false);
            resp.put("msg", "用户名和密码不能为空");
            return resp;
        }

        LoginVO vo = userService.login(dto.getUsername(), dto.getPassword());
        if (vo == null) {
            resp.put("ok", false);
            resp.put("msg", "用户名或密码错误");
            return resp;
        }

        resp.put("ok", true);
        resp.put("token", vo.getToken());
        resp.put("userId", vo.getUserId());
        resp.put("username", vo.getUsername());
        resp.put("nickname", vo.getNickname());
        return resp;
    }

    /**
     * 登出。从 Authorization 头取 token 删 Redis。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        userService.logout(token);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("msg", "已登出");
        return resp;
    }

    /**
     * 查当前登录用户信息（含 sms_key）。
     * 用户可在前端看到自己的 sms_key，用于配置手机端 SmsForwarder 的 webhook URL：
     *   http://<server>:8088/api/sms?token=<smsKey>
     */
    @GetMapping("/user/info")
    public Map<String, Object> info() {
        Long userId = UserContext.getUserId();
        Map<String, Object> resp = new LinkedHashMap<>();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        UserInfoVO vo = userService.getInfo(userId);
        if (vo == null) {
            resp.put("ok", false);
            resp.put("msg", "用户不存在");
            return resp;
        }

        resp.put("ok", true);
        resp.put("userId", vo.getUserId());
        resp.put("username", vo.getUsername());
        resp.put("nickname", vo.getNickname());
        resp.put("smsKey", vo.getSmsKey());
        return resp;
    }

    /** 从 Authorization: Bearer xxx 头提取 token */
    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return authHeader.substring(7).trim();
    }

    /**
     * 临时调试接口：重置 admin 密码（排查登录失败用）。
     * GET /api/reset-admin-password?raw=admin123
     * 会把 admin 的密码重置为 raw 参数指定的明文。
     * 调试完请删除此接口或保持关闭。
     */
    @GetMapping("/reset-admin-password")
    public Map<String, Object> resetAdminPassword(@RequestParam(defaultValue = "admin123") String raw) {
        Map<String, Object> resp = new LinkedHashMap<>();
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, "admin")
        );
        if (user == null) {
            resp.put("ok", false);
            resp.put("msg", "admin 用户不存在");
            return resp;
        }
        String newHash = passwordEncoder.encode(raw);
        user.setPassword(newHash);
        userMapper.updateById(user);
        resp.put("ok", true);
        resp.put("msg", "密码已重置");
        resp.put("newPassword", raw);
        resp.put("newHash", newHash);
        return resp;
    }
}
