package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日记媒体实体类，对应数据库表 t_diary_media。
 *
 * 字段说明：
 *   - diaryId    关联日记ID
 *   - userId     上传人(权限校验)
 *   - type       1=图片 2=语音
 *   - url        OSS公网访问URL
 *   - mime       MIME类型(image/jpeg, audio/webm等)
 *   - size       文件字节数
 *   - duration   语音时长(秒)，图片填0
 *   - sortOrder  同篇日记内排序(升序)
 *   - deleted    软删除：0=正常 1=已删除
 *   - createTime 创建时间
 */
@Data
@TableName("t_diary_media")
public class DiaryMedia {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long diaryId;
    private Long userId;
    private Integer type;       // 1=图片 2=语音
    private String url;
    private String mime;
    private Long size;
    private Integer duration;
    private Integer sortOrder;
    private Integer deleted;
    private LocalDateTime createTime;
}
