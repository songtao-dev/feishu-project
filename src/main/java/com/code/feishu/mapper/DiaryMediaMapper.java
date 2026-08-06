package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.DiaryMedia;
import org.apache.ibatis.annotations.Mapper;

/**
 * 日记媒体 Mapper（图片/语音）。
 *
 * 继承 BaseMapper 后自动拥有 insert/selectById/selectList/updateById/deleteById。
 * 查询条件用 LambdaQueryWrapper 在 Controller 里拼。
 */
@Mapper
public interface DiaryMediaMapper extends BaseMapper<DiaryMedia> {
}
