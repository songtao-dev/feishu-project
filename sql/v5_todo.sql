-- ============================================================
-- 待办事项表
-- 按用户隔离，支持完成/未完成状态切换
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_todo` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `content`     VARCHAR(500) NOT NULL                COMMENT '待办内容',
    `completed`   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否完成 0=未完成 1=已完成',
    `completed_at` DATETIME    NULL                    COMMENT '完成时间',
    `sort_order`  INT          NOT NULL DEFAULT 0      COMMENT '排序（越小越靠前）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_status` (`user_id`, `completed`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待办事项';
