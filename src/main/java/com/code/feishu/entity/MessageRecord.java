package com.code.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消息记录实体类，对应数据库表 t_message_record。
 *
 * 每收到一条银行短信（或用户手动输入一条），就往这个表写一条记录。
 * 同时会调用飞书 API 发群消息 + 写多维表格，并把这两个操作的结果状态记到这条记录上。
 *
 * 字段说明：
 *   - rawMessage  原始短信内容
 *   - bank        银行（如 工商银行）
 *   - cardTail    卡尾号
 *   - happenTime  交易时间（字符串原样保留，因为格式不固定）
 *   - direction   收支方向（支出/收入）
 *   - channel     支付渠道
 *   - merchant    商家
 *   - amount      金额
 *   - balance     余额
 *   - transType   消费分类
 *   - source      来源（manual=手动 / sms=短信转发）
 *   - feishuSent  飞书群消息是否发送成功
 *   - bitableSent 多维表格是否写入成功
 *   - createTime  记录创建时间
 */
@Data
@TableName("t_message_record")
public class MessageRecord {

    /** ===== 消息处理状态常量 ===== */
    public static final int STATUS_PENDING = 0;  // 待处理（刚入库，还没被消费者处理）
    public static final int STATUS_SUCCESS = 1;  // 已处理（飞书+表格都成功）
    public static final int STATUS_FAILED  = 2;  // 失败（重试耗尽，进死信队列）

    @TableId(type = IdType.AUTO)
    private Long id;

    private String rawMessage;
    private String bank;
    private String cardTail;
    private String happenTime;
    private String direction;
    private String channel;
    private String merchant;
    private BigDecimal amount;
    private BigDecimal balance;
    private String transType;
    private String source;

    /** 用户可见的序号（从1递增），AI 指令按此编号定位记录 */
    private Integer sortNum;

    /** 0=失败 1=成功，用 Integer 而不是 boolean，方便 SQL 里直接看 0/1 */
    private Integer feishuSent;
    private Integer bitableSent;

    /** 飞书多维表格记录 ID（写表格成功后存下来，删除/更新时用） */
    private String bitableRecordId;

    /** 处理状态：0=待处理 1=已处理 2=失败(进死信) —— 见状态常量 */
    private Integer status;
    /** MQ 重试次数 */
    private Integer retryCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
