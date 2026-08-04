package com.code.feishu.ai.dto;

import lombok.Data;

/**
 * AI 异步指令任务。
 *
 * 用户提交 AI 指令后，后端立即返回 taskId，
 * 异步处理完成后将结果存入此对象，前端轮询查询。
 */
@Data
public class AiCommandTask {
    /** 任务ID */
    private String taskId;
    /** 状态：pending / success / error */
    private String status;
    /** 回复内容 */
    private String reply;
    /** 创建时间戳（毫秒） */
    private long createTime;
}
