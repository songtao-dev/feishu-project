-- ============================================================
-- 日记媒体表（图片/语音），存储到阿里云 OSS
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_diary_media` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `diary_id`    BIGINT       NOT NULL                COMMENT '关联日记ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '上传人(权限校验)',
    `type`        TINYINT      NOT NULL                COMMENT '1=图片 2=语音',
    `url`         VARCHAR(512) NOT NULL                COMMENT 'OSS公网访问URL',
    `mime`        VARCHAR(32)  NULL                    COMMENT 'MIME类型(image/jpeg, audio/webm等)',
    `size`        BIGINT       NULL                    COMMENT '文件字节数',
    `duration`    INT          NULL DEFAULT 0          COMMENT '语音时长(秒)，图片填0',
    `sort_order`  INT          NOT NULL DEFAULT 0      COMMENT '同篇日记内排序(升序)',
    `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0=正常 1=已删除',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_diary` (`diary_id`),
    INDEX `idx_user_date` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日记媒体表(图片/语音)';
