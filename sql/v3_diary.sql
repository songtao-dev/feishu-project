-- ============================================================
-- v3 升级脚本：新增日记功能
-- 在已执行 init.sql + v2_user.sql 的库上追加执行
-- 执行方式：mysql -u root -p feishu_book < sql/v3_diary.sql
-- ============================================================

USE feishu_book;

-- 1. 创建日记表
CREATE TABLE IF NOT EXISTS `t_diary` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID（数据隔离）',
    `title`       VARCHAR(128) NULL                    COMMENT '日记标题（允许为空，纯随笔可不填）',
    `content`     TEXT         NOT NULL                COMMENT '日记正文',
    `mood`        VARCHAR(16)  NULL                    COMMENT '心情：very_happy/happy/ok/emo/bad/very_bad',
    `weather`     VARCHAR(16)  NULL                    COMMENT '天气：sunny/cloudy/rainy/snowy/windy/foggy',
    `tags`        VARCHAR(256) NULL                    COMMENT '标签，逗号分隔（如：生活,工作,感悟）',
    `diary_date`  DATE         NOT NULL                COMMENT '日记日期（按此字段做月度归档，用户可补写历史日记）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_date` (`user_id`, `diary_date`),
    INDEX `idx_user_mood` (`user_id`, `mood`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='随笔日记表';

-- 2. 验证
SELECT '日记表 t_diary 创建完成' AS info;
SHOW TABLES LIKE 't_diary';
DESC t_diary;
