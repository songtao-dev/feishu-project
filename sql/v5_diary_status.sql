-- ============================================
-- 日记发布状态升级脚本 (v5_diary_status.sql)
-- 功能：新增 status 字段，支持草稿/发布两种状态
-- ============================================

-- t_diary 表新增 status 字段
ALTER TABLE t_diary 
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'draft' 
  COMMENT '日记状态：draft=草稿(可编辑) / published=已发布(不可编辑)' 
  AFTER tags;

-- 创建索引：便于按状态筛选
-- （可选，如果需要按状态查询的话）
-- CREATE INDEX idx_diary_status ON t_diary (status);
