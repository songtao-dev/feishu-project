package com.code.feishu.consumer;

import com.code.feishu.config.RabbitMQConfig;
import com.code.feishu.dto.MessageSendDTO;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.MessageRecordMapper;
import com.code.feishu.service.FeishuBitableService;
import com.code.feishu.service.FeishuBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消息消费者。
 *
 * Controller 收到短信后只做「存库 + 投递MQ」，真正的「发飞书 + 写表格」在这里异步执行。
 *
 * 重试机制（由 Spring AMQP 自动完成）：
 *   - 处理失败 → 抛异常 → Spring 自动重试，间隔 1s/2s/4s，最多 3 次
 *   - 重试中如果成功了 → 正常 ack，消息从队列消失
 *   - 3 次都失败 → 消息进死信队列(msg.dlq)，数据库 status 标记为 FAILED
 *
 * 幂等设计（防重复消费）：
 *   - 如果飞书上次已发成功，重试时跳过不再发
 *   - 如果多维表格上次已写成功，重试时跳过不再写
 *   - 如果记录 status 已经是 SUCCESS，直接跳过
 */
@Component
public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    /** 最大重试次数，和 application.properties 里的 retry.max-attempts 保持一致 */
    private static final int MAX_RETRY = 3;

    private final MessageRecordMapper recordMapper;
    private final FeishuBotService bot;
    private final FeishuBitableService bitable;

    public MessageConsumer(MessageRecordMapper recordMapper,
                           FeishuBotService bot,
                           FeishuBitableService bitable) {
        this.recordMapper = recordMapper;
        this.bot = bot;
        this.bitable = bitable;
    }

    /**
     * 消费消息。消息体是数据库记录的 id（Long），消费者根据 id 去库里取完整记录。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void handle(Long recordId) {
        log.info("[MQ] 收到消息, recordId={}", recordId);

        MessageRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            log.warn("[MQ] 记录不存在, recordId={}, 丢弃", recordId);
            return; // 记录都不存在了，直接 ack 丢弃
        }

        // 幂等：已经处理成功的不再重复处理
        if (record.getStatus() != null && record.getStatus() == MessageRecord.STATUS_SUCCESS) {
            log.info("[MQ] 记录已处理过, 跳过, recordId={}", recordId);
            return;
        }

        // 重试次数 +1
        int retry = (record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1;
        record.setRetryCount(retry);

        // 从数据库记录恢复 DTO（发飞书和写表格要用）
        MessageSendDTO dto = buildDto(record);

        // ① 发飞书群消息（如果上次已成功则跳过，避免重复发送）
        boolean feishuOk = record.getFeishuSent() != null && record.getFeishuSent() == 1;
        if (!feishuOk) {
            try {
                String resp = bot.send(dto);
                feishuOk = resp != null && (resp.contains("\"StatusCode\":0") || resp.contains("StatusCode:0"));
            } catch (Exception e) {
                log.error("[MQ] 发飞书异常, recordId={}", recordId, e);
            }
        }
        record.setFeishuSent(feishuOk ? 1 : 0);

        // ② 写多维表格（如果上次已成功则跳过，避免重复写入）
        boolean bitableOk = record.getBitableSent() != null && record.getBitableSent() == 1;
        if (!bitableOk) {
            try {
                String resp = bitable.writeRecord(dto);
                bitableOk = resp != null && resp.contains("\"code\":0");
            } catch (Exception e) {
                log.error("[MQ] 写多维表格异常, recordId={}", recordId, e);
            }
        }
        record.setBitableSent(bitableOk ? 1 : 0);

        // ③ 更新状态
        if (feishuOk && bitableOk) {
            record.setStatus(MessageRecord.STATUS_SUCCESS);
            recordMapper.updateById(record);
            log.info("[MQ] 处理成功, recordId={}, 重试{}次", recordId, retry);
        } else {
            // 失败：如果已经达到最大重试次数，标记为 FAILED（消息会被 Spring 进死信队列）
            if (retry >= MAX_RETRY) {
                record.setStatus(MessageRecord.STATUS_FAILED);
            }
            recordMapper.updateById(record);
            log.warn("[MQ] 处理失败({}/{}), recordId={}, feishuOk={}, bitableOk={}",
                    retry, MAX_RETRY, recordId, feishuOk, bitableOk);
            // 抛异常 → 触发 Spring AMQP 自动重试
            throw new RuntimeException("消息处理失败, feishuOk=" + feishuOk + " bitableOk=" + bitableOk);
        }
    }

    /** 从数据库记录恢复 DTO */
    private MessageSendDTO buildDto(MessageRecord record) {
        MessageSendDTO dto = new MessageSendDTO();
        dto.setRawMessage(record.getRawMessage());
        dto.setBank(record.getBank());
        dto.setCardTail(record.getCardTail());
        dto.setHappenTime(record.getHappenTime());
        dto.setDirection(record.getDirection());
        dto.setChannel(record.getChannel());
        dto.setMerchant(record.getMerchant());
        dto.setAmount(record.getAmount());
        dto.setBalance(record.getBalance());
        dto.setTransType(record.getTransType());
        return dto;
    }
}
