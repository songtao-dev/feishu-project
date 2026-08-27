-- ============================================================
-- 记事本模块（仿小米记事本）
-- 包含：记事表 + 分类表
-- 支持置顶(pinned)、收藏(starred)、分类(category_id)、软删除(deleted)
-- ============================================================

-- 记事本分类表
CREATE TABLE IF NOT EXISTS `t_note_category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `name`        VARCHAR(20)  NOT NULL                COMMENT '分类名',
    `color`       VARCHAR(16)  NULL DEFAULT '#1a1a1a'  COMMENT '分类颜色(十六进制)',
    `sort_order`  INT          NOT NULL DEFAULT 0      COMMENT '排序（越小越靠前）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `sort_order`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记事本分类';

-- 记事表
CREATE TABLE IF NOT EXISTS `t_note` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `title`       VARCHAR(100) NULL                    COMMENT '标题(可为空)',
    `content`     TEXT         NULL                    COMMENT '正文内容',
    `category_id` BIGINT       NULL                    COMMENT '分类ID(t_note_category.id, 可为空)',
    `pinned`      TINYINT      NOT NULL DEFAULT 0      COMMENT '是否置顶 0=否 1=是',
    `starred`     TINYINT      NOT NULL DEFAULT 0      COMMENT '是否收藏 0=否 1=是',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '软删除 0=正常 1=已删除',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_pinned` (`user_id`, `deleted`, `pinned`, `update_time`),
    KEY `idx_user_category` (`user_id`, `category_id`, `deleted`),
    KEY `idx_user_starred` (`user_id`, `starred`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记事本';

-- 预置几个默认分类（对所有已存在的用户）
-- 如果不想预置，可注释掉这段
INSERT INTO `t_note_category` (`user_id`, `name`, `color`, `sort_order`)
SELECT u.id, '生活', '#ff7043', 0 FROM `t_user` u
WHERE NOT EXISTS (SELECT 1 FROM `t_note_category` c WHERE c.user_id = u.id AND c.name = '生活');

INSERT INTO `t_note_category` (`user_id`, `name`, `color`, `sort_order`)
SELECT u.id, '工作', '#42a5f5', 1 FROM `t_user` u
WHERE NOT EXISTS (SELECT 1 FROM `t_note_category` c WHERE c.user_id = u.id AND c.name = '工作');

INSERT INTO `t_note_category` (`user_id`, `name`, `color`, `sort_order`)
SELECT u.id, '学习', '#66bb6a', 2 FROM `t_user` u
WHERE NOT EXISTS (SELECT 1 FROM `t_note_category` c WHERE c.user_id = u.id AND c.name = '学习');
