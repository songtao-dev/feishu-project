package com.code.feishu.job;

import com.code.feishu.config.RabbitMQConfig;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.MessageRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底定时任务。
 *
 * 场景：MQ 宕机 / 消费者全挂了 / 消息投递失败
 *   → 消息虽然入库了（status=待处理），但没被消费者处理
 *   → 这里每分钟扫一次，把超时的「待处理」记录重新投递到 MQ
 *
 * 和 MQ 形成双保险：
 *   - MQ 正常时：消息靠消费者异步处理
 *   - MQ 异常时：靠这个定时任务把漏掉的消息补上
 */
@Component
public class PendingMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingMessageRetryJob.class);

    @Autowired
    private MessageRecordMapper recordMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 每 60 秒执行一次。
     * 找出 status=0（待处理）且创建时间在 2 分钟前的记录（避免刚入库还没来得及消费就被重投）。
     */
    @Scheduled(fixedDelay = 60000)
    public void retryPending() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);

        List<MessageRecord> pending = recordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageRecord>()
                        .eq(MessageRecord::getStatus, MessageRecord.STATUS_PENDING)
                        .lt(MessageRecord::getCreateTime, threshold)
                        .last("LIMIT 50")
        );

        if (pending.isEmpty()) {
            return;
        }

        log.info("[兜底任务] 发现 {} 条待处理超时记录，重新投递MQ", pending.size());
        for (MessageRecord r : pending) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE,
                        RabbitMQConfig.ROUTING_KEY,
                        r.getId()
                );
                log.info("[兜底任务] 重新投递 recordId={}", r.getId());
            } catch (Exception e) {
                log.error("[兜底任务] 投递失败 recordId={}", r.getId(), e);
            }
        }
    }
}
