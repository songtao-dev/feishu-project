-- ============================================================
-- 日记自动发布配置表
-- 每个用户一条配置，记录自动发布的小时（0-23）
-- 定时任务每小时整点扫描，匹配到点的用户 → 发布其所有草稿日记
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_diary_publish_config` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `publish_hour` TINYINT     NOT NULL DEFAULT 1      COMMENT '自动发布小时（0-23），默认1点',
    `enabled`     TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用 1=启用 0=禁用',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日记自动发布配置';

-- 为已有用户初始化默认配置（publish_hour=1, enabled=1）
INSERT INTO `t_diary_publish_config` (`user_id`, `publish_hour`, `enabled`)
SELECT u.id, 1, 1
FROM `t_user` u
WHERE NOT EXISTS (
    SELECT 1 FROM `t_diary_publish_config` c WHERE c.user_id = u.id
);
