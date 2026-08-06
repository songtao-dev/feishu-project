-- ============================================================
-- 给记账/日记/待办表加 deleted 软删除字段
-- ============================================================

-- 记账记录
ALTER TABLE t_message_record
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除' AFTER bitable_record_id,
    ADD INDEX idx_user_deleted (user_id, deleted);

-- 日记
ALTER TABLE t_diary
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除' AFTER status,
    ADD INDEX idx_user_deleted_diary (user_id, deleted);

-- 待办
ALTER TABLE t_todo
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除' AFTER sort_order,
    ADD INDEX idx_user_deleted_todo (user_id, deleted);
