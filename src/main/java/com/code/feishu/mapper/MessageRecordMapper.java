package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.MessageRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息记录 Mapper。
 *
 * 继承 MyBatis-Plus 的 BaseMapper 后，自动拥有以下方法（不用写 SQL）：
 *   insert(entity)              插入一条记录
 *   selectById(id)              按主键查
 *   selectList(null)            查全部
 *   updateById(entity)          按主键更新
 *   deleteById(id)              按主键删
 *   selectPage(page, wrapper)   分页查询
 *
 * 如果需要复杂 SQL，可以在 resources/mapper/ 下写 XML 文件，或者在接口方法上加 @Select 注解。
 */
@Mapper
public interface MessageRecordMapper extends BaseMapper<MessageRecord> {
}
