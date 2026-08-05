package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.DiaryGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 共享日记本 Mapper。继承 BaseMapper 自动拥有 insert/selectById/selectList 等方法。
 */
@Mapper
public interface DiaryGroupMapper extends BaseMapper<DiaryGroup> {
}
