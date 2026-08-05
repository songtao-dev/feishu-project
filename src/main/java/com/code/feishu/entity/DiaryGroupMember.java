package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享日记本成员关系实体，对应数据库表 t_diary_group_member。
 *
 * 字段说明：
 *   - groupId    所属共享日记本ID
 *   - userId     用户ID
 *   - role       角色：owner=组主 / member=普通成员
 *   - status     状态：pending=待确认 / active=已加入 / left=已退出
 *   - joinTime   变为 active 的时间
 *
 * 一个 (group_id, user_id) 唯一，退出后状态置 left，不可重复加入（同一组同用户只一条记录）。
 */
@Data
@TableName("t_diary_group_member")
public class DiaryGroupMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;
    private String role;
    private String status;
    private LocalDateTime joinTime;
    private LocalDateTime createTime;
}
