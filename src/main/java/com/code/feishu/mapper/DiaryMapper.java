package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.Diary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 日记 Mapper。
 *
 * 继承 MyBatis-Plus 的 BaseMapper 后，自动拥有以下方法（不用写 SQL）：
 *   insert(entity)              插入一条日记
 *   selectById(id)              按主键查
 *   selectList(wrapper)         按条件查列表
 *   updateById(entity)          按主键更新
 *   deleteById(id)              按主键删
 *
 * 月度查询用 LambdaQueryWrapper 在 Controller 里拼条件即可，无需写 XML。
 */
@Mapper
public interface DiaryMapper extends BaseMapper<Diary> {
}
