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
     * 更新当前用户昵称。
     * PUT /api/user/nickname
     * 请求：{"nickname":"新昵称"}
     */
    @PutMapping("/user/nickname")
    public Map<String, Object> updateNickname(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isBlank()) {
            resp.put("ok", false);
            resp.put("msg", "昵称不能为空");
            return resp;
        }
        if (nickname.length() > 32) {
            resp.put("ok", false);
            resp.put("msg", "昵称最多32个字符");
            return resp;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            resp.put("ok", false);
            resp.put("msg", "用户不存在");
            return resp;
        }
        user.setNickname(nickname.trim());
        userMapper.updateById(user);

        // 更新本地缓存
        UserInfoVO vo = userService.getInfo(userId);
        resp.put("ok", true);
        resp.put("nickname", vo != null ? vo.getNickname() : nickname);
        return resp;
    }

    /**
     * 修改密码（已登录用户）。
     * PUT /api/user/password
     * body: { "oldPassword": "xxx", "newPassword": "yyy" }
     */
    @PutMapping("/user/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false); resp.put("msg", "未登录"); return resp;
        }
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) {
            resp.put("ok", false); resp.put("msg", "请输入原密码"); return resp;
        }
        if (newPassword == null || newPassword.length() < 6) {
            resp.put("ok", false); resp.put("msg", "新密码至少6位"); return resp;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            resp.put("ok", false); resp.put("msg", "用户不存在"); return resp;
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            resp.put("ok", false); resp.put("msg", "原密码错误"); return resp;
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        resp.put("ok", true); resp.put("msg", "密码修改成功");
        return resp;
    }
}
