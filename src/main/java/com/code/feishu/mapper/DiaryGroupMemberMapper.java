package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.DiaryGroupMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 共享日记本成员关系 Mapper。继承 BaseMapper 自动拥有常用方法。
 */
@Mapper
public interface DiaryGroupMemberMapper extends BaseMapper<DiaryGroupMember> {
}
