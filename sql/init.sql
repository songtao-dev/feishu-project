-- ============================================================
-- 飞书记账项目 数据库初始化脚本
-- 数据库：MySQL 8.0+
-- 字符集：utf8mb4（支持 emoji 和中文）
-- ============================================================

-- 1. 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS feishu_book
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 2. 使用数据库
USE feishu_book;

-- 3. 删除旧表（首次执行可忽略，重新初始化时用）
DROP TABLE IF EXISTS `t_message_record`;

-- 4. 创建消息记录表
CREATE TABLE `t_message_record` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `raw_message`   TEXT         NULL                    COMMENT '原始短信内容（银行短信原文）',
    `bank`          VARCHAR(32)  NULL                    COMMENT '银行名称（如 工商银行）',
    `card_tail`     VARCHAR(8)   NULL                    COMMENT '银行卡尾号（后4位）',
    `happen_time`   VARCHAR(32)  NULL                    COMMENT '交易发生时间（字符串，原样保留）',
    `direction`     VARCHAR(8)   NULL                    COMMENT '收支方向：支出/收入',
    `channel`       VARCHAR(32)  NULL                    COMMENT '支付渠道（如 抖音支付/支付宝/微信）',
    `merchant`      VARCHAR(128) NULL                    COMMENT '商家名称',
    `amount`        DECIMAL(12,2) NULL                   COMMENT '交易金额（元）',
    `balance`       DECIMAL(12,2) NULL                   COMMENT '账户余额（元）',
    `trans_type`    VARCHAR(32)  NULL                    COMMENT '消费分类（如 餐饮/交通/购物）',
    `source`        VARCHAR(16)  NULL DEFAULT 'manual'   COMMENT '来源：manual=手动输入 / sms=短信转发',
    `feishu_sent`   TINYINT(1)   NULL DEFAULT 0          COMMENT '飞书群消息是否发送成功：0=失败 1=成功',
    `bitable_sent`  TINYINT(1)   NULL DEFAULT 0          COMMENT '多维表格是否写入成功：0=失败 1=成功',
    `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '处理状态：0=待处理 1=已处理 2=失败(进死信)',
    `retry_count`   INT          NOT NULL DEFAULT 0      COMMENT 'MQ重试次数',
    `create_time`   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    `update_time`   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_create_time` (`create_time`),
    INDEX `idx_happen_time` (`happen_time`),
    INDEX `idx_direction`   (`direction`),
    INDEX `idx_status`      (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息记录表（每条银行短信/手动输入一条记录）';

-- 5. 验证表创建成功
SELECT '表创建成功: t_message_record' AS info;
SHOW TABLES;
DESC t_message_record;
