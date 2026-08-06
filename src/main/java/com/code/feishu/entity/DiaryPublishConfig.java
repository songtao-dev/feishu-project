package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日记自动发布配置实体类。
 *
 * 每个用户一条配置（user_id 唯一），记录：
 *   - publishHour: 自动发布的小时（0-23），定时任务每小时扫描，到点发布该用户所有草稿日记
 *   - enabled:     是否启用（1=启用 0=禁用）
 */
@Data
@TableName("t_diary_publish_config")
public class DiaryPublishConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Integer publishHour;   // 0-23
    private Integer enabled;       // 1=启用 0=禁用
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
