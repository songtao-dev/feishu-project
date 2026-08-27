package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记事本分类实体。
 *
 *   - userId:    用户隔离
 *   - name:      分类名（如 生活/工作/学习）
 *   - color:     分类颜色（十六进制，如 #ff7043），用于前端标签着色
 *   - sortOrder: 排序，越小越靠前
 */
@Data
@TableName("t_note_category")
public class NoteCategory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String color;
    private Integer sortOrder;
    private LocalDateTime createTime;
}
