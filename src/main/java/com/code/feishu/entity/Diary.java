package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 随笔日记实体类，对应数据库表 t_diary。
 *
 * 字段说明：
 *   - userId      用户ID（数据隔离，所有查询按此过滤）
 *   - title       标题（可空，纯随笔可不填）
 *   - content     正文（必填）
 *   - mood        心情枚举：very_happy/happy/ok/emo/bad/very_bad
 *   - weather     天气枚举：sunny/cloudy/rainy/snowy/windy/foggy
 *   - tags        标签，逗号分隔字符串（如 "生活,工作,感悟"）
 *   - diaryDate   日记日期（按此字段做月度归档，用户可补写历史日记）
 *   - createTime  记录创建时间
 *   - updateTime  记录更新时间
 *
 * 心情枚举对照（前端做映射，后端只存英文 key）：
 *   very_happy = 非常开心 😄
 *   happy      = 开心 🙂
 *   ok         = 不错 😐
 *   emo        = 有点emo 😔
 *   bad        = 有点糟糕 ☹️
 *   very_bad   = 很糟糕 😢
 */
@Data
@TableName("t_diary")
public class Diary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String content;
    private String mood;
    private String weather;
    private String tags;
    private LocalDate diaryDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
