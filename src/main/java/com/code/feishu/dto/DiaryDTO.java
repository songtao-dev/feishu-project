package com.code.feishu.dto;

import lombok.Data;

/**
 * 日记新建/更新请求 DTO。
 *
 * 新建：所有字段可选除 content 外（content 必填）。
 * 更新：只更传了字段（Controller 用 containsKey 判断）。
 */
@Data
public class DiaryDTO {
    /** 标题（可空） */
    private String title;
    /** 正文（必填） */
    private String content;
    /** 心情：very_happy/happy/ok/emo/bad/very_bad */
    private String mood;
    /** 天气：sunny/cloudy/rainy/snowy/windy/foggy */
    private String weather;
    /** 标签，逗号分隔（如 "生活,工作"） */
    private String tags;
    /** 日记日期（YYYY-MM-DD，不传默认今天） */
    private String diaryDate;
}
