package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.Todo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待办事项 Mapper。
 */
@Mapper
public interface TodoMapper extends BaseMapper<Todo> {
}
