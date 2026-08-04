package com.code.feishu.vo;

import lombok.Data;

/**
 * 用户信息（脱敏，不含密码）。
 * 用于 /api/user/info 返回当前登录用户的信息，包括 sms_key（方便用户配置 SmsForwarder）。
 */
@Data
public class UserInfoVO {
    private Long userId;
    private String username;
    private String nickname;
    private String smsKey;
}
