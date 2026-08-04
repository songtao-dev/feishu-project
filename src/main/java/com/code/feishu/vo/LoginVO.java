package com.code.feishu.vo;

import lombok.Data;

/**
 * 登录成功返回对象。
 */
@Data
public class LoginVO {
    private String token;
    private Long userId;
    private String username;
    private String nickname;
}
