package com.code.feishu.dto;

import lombok.Data;

/**
 * 登录请求 DTO。
 */
@Data
public class LoginDTO {
    private String username;
    private String password;
}
