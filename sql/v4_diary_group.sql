-- ============================================================
-- v4 升级脚本：多人共同编辑日记树（共享日记本）
-- 在已执行 init.sql + v2_user.sql + v3_diary.sql 的库上追加执行
-- 执行方式：mysql -u root -p feishu_book < sql/v4_diary_group.sql
-- ============================================================

USE feishu_book;

-- ============================================================
-- 1. 多人日记本（共享空间）表
--    一个共享日记本 = 一棵多人共写的时间轴
--    创建者 = owner，可邀请最多 max_members(默认8) 人共同写日记
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_diary_group` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`         VARCHAR(64)  NOT NULL                COMMENT '共享日记本名称',
    `owner_id`     BIGINT       NOT NULL                COMMENT '创建者用户ID（组主）',
    `max_members`  INT          NOT NULL DEFAULT 8      COMMENT '成员上限（含组主）',
    `invite_code`  VARCHAR(32)  NULL                    COMMENT '邀请码（凭此码申请加入，需组主确认）',
    `create_time`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invite_code` (`invite_code`),
    INDEX `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多人共享日记本表';

-- ============================================================
-- 2. 成员关系表
--    status 流转：
--      pending  邀请/申请发出，等待确认
--      active   已加入（可读组内日记、可写自己的日记）
--      left     已退出（不能再访问）
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_diary_group_member` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `group_id`    BIGINT       NOT NULL                COMMENT '所属共享日记本ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT '角色：owner=组主 / member=普通成员',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending=待确认 / active=已加入 / left=已退出',
    `join_time`   DATETIME     NULL                    COMMENT '加入时间（变为 active 时写入）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    INDEX `idx_user_status` (`user_id`, `status`),
    INDEX `idx_group_status` (`group_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='共享日记本成员关系表';

-- ============================================================
-- 3. 扩展 t_diary 表
--    group_id 为 NULL  → 私人日记（按 user_id 隔离，原逻辑不变）
--    group_id 非 NULL  → 共享日记本日记
--       - user_id      仍保留（兼容旧逻辑，私人日记用）
--       - author_user_id 冗余作者ID，共享日记用，便于查询和权限校验
--    权限规则：共享日记只能由 author_user_id 本人修改/删除，
--             组主也不能改删别人的日记（用户明确要求）。
-- ============================================================
DROP PROCEDURE IF EXISTS add_diary_group_columns;
DELIMITER //
CREATE PROCEDURE add_diary_group_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_diary'
          AND COLUMN_NAME = 'group_id'
    ) THEN
        ALTER TABLE `t_diary` ADD COLUMN `group_id` BIGINT NULL COMMENT '共享日记本ID（NULL=私人日记）' AFTER `user_id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_diary'
          AND COLUMN_NAME = 'author_user_id'
    ) THEN
        ALTER TABLE `t_diary` ADD COLUMN `author_user_id` BIGINT NULL COMMENT '作者用户ID（共享日记用，冗余字段）' AFTER `group_id`;
    END IF;
END //
DELIMITER ;
CALL add_diary_group_columns();
DROP PROCEDURE add_diary_group_columns;

-- 给共享日记查询加索引（group_id + diary_date）
DROP PROCEDURE IF EXISTS add_idx_diary_group_date;
DELIMITER //
CREATE PROCEDURE add_idx_diary_group_date()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_diary'
          AND INDEX_NAME = 'idx_group_date'
    ) THEN
        CREATE INDEX `idx_group_date` ON `t_diary` (`group_id`, `diary_date`);
    END IF;
END //
DELIMITER ;
CALL add_idx_diary_group_date();
DROP PROCEDURE add_idx_diary_group_date;

-- 4. 验证
SELECT '共享日记本表 t_diary_group 创建完成' AS info;
SHOW TABLES LIKE 't_diary_group';
DESC t_diary_group;

SELECT '成员关系表 t_diary_group_member 创建完成' AS info;
SHOW TABLES LIKE 't_diary_group_member';
DESC t_diary_group_member;

SELECT 't_diary 表已扩展 group_id / author_user_id 字段' AS info;
DESC t_diary;
