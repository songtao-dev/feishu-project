package com.code.feishu.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类。
 *
 * 拓扑结构：
 *
 *   生产者 ──→ msg.exchange(Direct) ──→ msg.queue（主队列）
 *                                        │ 消息被 reject/nack(requeue=false)
 *                                        ↓
 *                                   msg.dlx.exchange(Direct) ──→ msg.dlq（死信队列）
 *
 *   - 主队列的消息被消费者处理，成功就 ack
 *   - 处理失败 → Spring 自动重试 3 次（1s/2s/4s 间隔）
 *   - 重试耗尽 → 消息进入死信队列（人工兜底 / 后续可加告警）
 */
@Configuration
public class RabbitMQConfig {

    // ===== 交换机名称 =====
    public static final String EXCHANGE      = "msg.exchange";       // 主交换机
    public static final String DLX_EXCHANGE  = "msg.dlx.exchange";   // 死信交换机

    // ===== 队列名称 =====
    public static final String QUEUE     = "msg.queue";   // 主队列
    public static final String DLQ_QUEUE = "msg.dlq";     // 死信队列

    // ===== 路由键 =====
    public static final String ROUTING_KEY     = "msg.send";
    public static final String DLQ_ROUTING_KEY = "msg.dlq";

    // ---------------- 主交换机（Direct）----------------
    @Bean
    public DirectExchange msgExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    // ---------------- 死信交换机（Direct）----------------
    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    // ---------------- 主队列 ----------------
    // 关键：绑定死信交换机，消息被 reject(requeue=false) 时自动转发到死信队列
    @Bean
    public Queue msgQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    // ---------------- 死信队列 ----------------
    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    // ---------------- 绑定 ----------------
    @Bean
    public Binding msgBinding(Queue msgQueue, DirectExchange msgExchange) {
        return BindingBuilder.bind(msgQueue).to(msgExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with(DLQ_ROUTING_KEY);
    }

    // ---------------- 消息转换器 ----------------
    // 用 JSON 序列化，发送 Long / 对象都方便，消费者也能自动反序列化
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
