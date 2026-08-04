package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体。
 */
@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 加密后的密码 */
    private String password;
    private String nickname;
    /** SMS 转发密钥（SmsForwarder 调用 /api/sms 时以此定位用户） */
    private String smsKey;
    private LocalDateTime createTime;
}
