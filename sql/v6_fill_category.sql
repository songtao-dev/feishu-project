-- ============================================================
-- 给已有数据补全 transType 分类（可选执行）
-- 根据 merchant 关键词自动匹配分类
-- ============================================================

UPDATE t_message_record SET trans_type = '餐饮'  WHERE trans_type IS NULL AND (merchant LIKE '%餐饮%' OR merchant LIKE '%饭%' OR merchant LIKE '%餐%' OR merchant LIKE '%外卖%' OR merchant LIKE '%食堂%');
UPDATE t_message_record SET trans_type = '交通'  WHERE trans_type IS NULL AND (merchant LIKE '%加油%' OR merchant LIKE '%停车%' OR merchant LIKE '%高速%' OR merchant LIKE '%地铁%' OR merchant LIKE '%公交%' OR merchant LIKE '%打车%' OR merchant LIKE '%滴滴%');
UPDATE t_message_record SET trans_type = '购物'  WHERE trans_type IS NULL AND (merchant LIKE '%超市%' OR merchant LIKE '%淘宝%' OR merchant LIKE '%京东%' OR merchant LIKE '%拼多多%' OR merchant LIKE '%购物%' OR merchant LIKE '%商城%');
UPDATE t_message_record SET trans_type = '娱乐'  WHERE trans_type IS NULL AND (merchant LIKE '%电影%' OR merchant LIKE '%KTV%' OR merchant LIKE '%游戏%' OR merchant LIKE '%门票%');
UPDATE t_message_record SET trans_type = '医疗'  WHERE trans_type IS NULL AND (merchant LIKE '%医院%' OR merchant LIKE '%药%' OR merchant LIKE '%诊所%');
UPDATE t_message_record SET trans_type = '教育'  WHERE trans_type IS NULL AND (merchant LIKE '%学费%' OR merchant LIKE '%培训%' OR merchant LIKE '%书%');
UPDATE t_message_record SET trans_type = '居家'  WHERE trans_type IS NULL AND (merchant LIKE '%水电%' OR merchant LIKE '%物业%' OR merchant LIKE '%房租%' OR merchant LIKE '%燃气%');
UPDATE t_message_record SET trans_type = '其他'  WHERE trans_type IS NULL;
