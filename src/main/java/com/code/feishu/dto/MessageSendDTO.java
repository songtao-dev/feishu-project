package com.code.feishu.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 前端发送消息的请求体。
 * 两种模式：
 *   1）只传 rawMessage（完整短信原文），后端会自动解析再拼装飞书消息
 *   2）传拆分字段（amount / merchant / balance 等），后端按字段拼装
 */
@Data
public class MessageSendDTO {

    /** 模式 1：完整原始消息（例如银行短信） */
    private String rawMessage;

    /** 模式 2：尾号后四位 */
    private String cardTail;

    /** 模式 2：消费日期时间字符串 yyyy-MM-dd HH:mm 或任意文本 */
    private String happenTime;

    /** 模式 2：收入/支出 */
    private String direction;

    /** 模式 2：支付渠道（如 消费抖音支付） */
    private String channel;

    /** 模式 2：商家名称 */
    private String merchant;

    /** 模式 2：金额（元） */
    private BigDecimal amount;

    /** 模式 2：余额（元） */
    private BigDecimal balance;

    /** 模式 2：银行名称 */
    private String bank;

    /** 模式 2：交易类型（支出/收入 等），和 direction 重复保留一个即可；这里允许前端覆盖 */
    private String transType;
}
