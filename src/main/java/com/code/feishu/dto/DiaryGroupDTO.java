package com.code.feishu.dto;

import lombok.Data;

import java.util.List;

/**
 * 共享日记本相关请求 DTO。
 *
 * 用于创建/邀请等接口的请求体。
 */
@Data
public class DiaryGroupDTO {

    /** 创建共享日记本：名称 */
    private String name;

    /** 创建时同时邀请的用户名列表（可选） */
    private List<String> inviteUsernames;

    /** 邀请成员接口：被邀请的用户名 */
    private String username;

    /** 凭邀请码加入接口：邀请码 */
    private String inviteCode;
}
