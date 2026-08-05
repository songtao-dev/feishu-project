package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多人共享日记本实体类，对应数据库表 t_diary_group。
 *
 * 一个共享日记本 = 一棵多人共写的时间轴。
 *   - ownerId     创建者（组主）
 *   - maxMembers  成员上限（含组主），默认 8
 *   - inviteCode  邀请码，凭此码申请加入（需组主确认）
 *
 * 权限规则：
 *   - 组内所有 active 成员都能查看组内全部日记
 *   - 日记只能由作者本人（author_user_id）修改/删除
 *   - 组主可邀请/移除成员、确认申请；但不能改删别人的日记
 *   - 组主退出时，所有权转交给最早加入的 active 成员
 */
@Data
@TableName("t_diary_group")
public class DiaryGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long ownerId;
    private Integer maxMembers;
    private String inviteCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
