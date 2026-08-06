package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.DiaryPublishConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 日记发布配置 Mapper。继承 BaseMapper 自动拥有基础 CRUD。
 */
@Mapper
public interface DiaryPublishConfigMapper extends BaseMapper<DiaryPublishConfig> {
}
