package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记事本实体（仿小米记事本）。
 *
 *   - userId:     用户隔离
 *   - categoryId: 所属分类（t_note_category.id），可为空
 *   - pinned:     0=普通 1=置顶（置顶排最前）
 *   - starred:    0=未收藏 1=已收藏
 *   - deleted:    软删除：0=正常 1=已删除
 */
@Data
@TableName("t_note")
public class Note {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private Long categoryId;
    private Integer pinned;
    private Integer starred;
    /** 软删除：0=正常 1=已删除 */
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
