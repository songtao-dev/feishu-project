package com.code.feishu.ai.dto;

import lombok.Data;

import java.util.Map;

/**
 * AI 指令解析 + 执行结果。
 *
 * AI 理解用户意图后，返回一个结构化指令，后端执行后填充结果。
 *
 * 指令类型（action）：
 *   - delete  删除记录
 *   - update  更新记录
 *   - query   查询记录
 *   - unknown 无法理解意图
 *
 * 目标定位（target）：
 *   - type=index    按序号（用户说"第三条"，value=3）
 *   - type=latest   最新一条（用户说"刚才那条"、"刚刚那条"）
 *   - type=merchant 按商家名（value=商家名）
 *   - type=id       按 ID（value=记录ID）
 */
@Data
public class AiCommandResult {

    /** 解析是否成功 */
    private boolean success;

    /** 指令类型：delete / update / query / unknown */
    private String action;

    /** 目标定位类型：index / latest / merchant / id */
    private String targetType;

    /** 目标值（序号/商家名/ID，latest 时为 null） */
    private String targetValue;

    /** 要更新的字段（action=update 时有效） */
    private Map<String, Object> fields;

    /** 执行结果（删除的记录、更新后的记录、查询到的记录列表） */
    private Object result;

    /** 给用户的自然语言回复 */
    private String reply;

    /** 解析/执行失败时的错误信息 */
    private String errorMsg;

    /** AI 原始返回（调试用） */
    private String rawResponse;
}
