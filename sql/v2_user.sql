-- ============================================================
-- v2 升级脚本：添加用户系统 + 数据隔离
-- 在已执行 init.sql 的库上追加执行
-- 执行方式：mysql -u root -p feishu_book < sql/v2_user.sql
-- ============================================================

USE feishu_book;

-- 1. 创建用户表（如不存在）
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT 'BCrypt加密后的密码',
    `nickname`    VARCHAR(64)  NULL     COMMENT '昵称',
    `sms_key`     VARCHAR(64)  NULL     COMMENT 'SMS转发密钥（SmsForwarder调用/api/sms时带此key定位用户）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_sms_key` (`sms_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 2. 消息记录表加 user_id 列（数据隔离用）
-- 用 PROCEDURE 判断列是否存在，避免重复执行报错
DROP PROCEDURE IF EXISTS add_user_id_column;
DELIMITER //
CREATE PROCEDURE add_user_id_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_message_record'
          AND COLUMN_NAME = 'user_id'
    ) THEN
        ALTER TABLE `t_message_record` ADD COLUMN `user_id` BIGINT NULL COMMENT '用户ID（数据隔离）' AFTER `id`;
    END IF;
END //
DELIMITER ;
CALL add_user_id_column();
DROP PROCEDURE add_user_id_column;

-- 3. 给 user_id 加索引（如不存在）
DROP PROCEDURE IF EXISTS add_idx_user_id;
DELIMITER //
CREATE PROCEDURE add_idx_user_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_message_record'
          AND INDEX_NAME = 'idx_user_id'
    ) THEN
        CREATE INDEX `idx_user_id` ON `t_message_record` (`user_id`);
    END IF;
END //
DELIMITER ;
CALL add_idx_user_id();
DROP PROCEDURE add_idx_user_id;

-- 4. 验证
SELECT '用户表 t_user 创建完成' AS info;
SHOW TABLES LIKE 't_user';
DESC t_user;

SELECT '消息记录表 user_id 列添加完成' AS info;
DESC t_message_record;

-- ============================================================
-- 5. 创建第一个管理员账号（手动执行，按需修改）
--    明文密码 admin123 的 BCrypt 哈希值如下：
--    $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
--    sms_key 自己起一个随机字符串，配置 SmsForwarder 时用
-- ============================================================
-- INSERT INTO t_user(username, password, nickname, sms_key)
-- VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '管理员', 'your-sms-key-here');

-- 6. 把已有消息记录归到管理员名下（避免老数据 user_id 为空查不到）
-- UPDATE t_message_record
-- SET user_id = (SELECT id FROM t_user WHERE username = 'admin')
-- WHERE user_id IS NULL;
