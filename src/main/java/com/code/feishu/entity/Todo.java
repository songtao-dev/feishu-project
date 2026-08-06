package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待办事项实体。
 *
 *   - userId:     用户隔离
 *   - completed:  0=未完成 1=已完成
 *   - completedAt:完成时间
 *   - sortOrder:  排序，越小越靠前（新建的默认 0，即排在最前）
 */
@Data
@TableName("t_todo")
public class Todo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String content;
    private Integer completed;
    private LocalDateTime completedAt;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
