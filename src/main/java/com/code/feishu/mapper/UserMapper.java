package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。继承 BaseMapper 自动拥有 insert/selectById/selectOne 等方法。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
