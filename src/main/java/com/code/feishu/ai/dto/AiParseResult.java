package com.code.feishu.ai.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 解析结果。
 *
 * 和 MessageParseVO 结构类似，但独立维护，避免 AI 模块和现有模块耦合。
 * 后续如果 AI 返回更多字段，在这里加即可。
 */
@Data
public class AiParseResult {

    /** 银行名称 */
    private String bank;

    /** 卡号尾号 */
    private String cardTail;

    /** 消费时间 */
    private String happenTime;

    /** 收支方向（支出/收入） */
    private String direction;

    /** 支付渠道 */
    private String channel;

    /** 商家名称 */
    private String merchant;

    /** 金额 */
    private BigDecimal amount;

    /** 余额 */
    private BigDecimal balance;

    /** 交易类型 */
    private String transType;

    /** 解析是否成功 */
    private boolean success;

    /** AI 原始返回（调试用） */
    private String rawResponse;

    /** 解析失败时的错误信息 */
    private String errorMsg;
}
