package com.code.feishu.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 解析结果：把用户粘进来的完整银行短信拆成字段，回显到前端表单。
 */
@Data
public class MessageParseVO {
    /** 是否解析成功（哪怕部分字段解析不到也是 true） */
    private Boolean success = true;
    /** 提示信息（失败时或部分未命中时给前端看） */
    private String tip;

    private String cardTail;
    private String happenTime;
    private String direction;   // 支出/收入
    private String channel;
    private String merchant;
    private BigDecimal amount;
    private BigDecimal balance;
    private String bank;
    private String transType;
}
