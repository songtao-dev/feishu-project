package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.entity.Note;
import com.code.feishu.entity.NoteCategory;
import com.code.feishu.mapper.NoteCategoryMapper;
import com.code.feishu.mapper.NoteMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 记事本分类接口。
 *
 *   GET    /api/note-category           查询当前用户所有分类（带每个分类下的记事数）
 *   POST   /api/note-category           新建分类 {"name","color"}
 *   PUT    /api/note-category/{id}      更新分类 {"name","color"}
 *   DELETE /api/note-category/{id}      删除分类（其下记事的 categoryId 置空）
 */
@RestController
@RequestMapping("/api/note-category")
public class NoteCategoryController {

    private final NoteCategoryMapper categoryMapper;
    private final NoteMapper noteMapper;

    public NoteCategoryController(NoteCategoryMapper categoryMapper, NoteMapper noteMapper) {
        this.categoryMapper = categoryMapper;
        this.noteMapper = noteMapper;
    }

    /** 查询所有分类（按 sortOrder 升序），并附带每个分类下的记事数 */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        List<NoteCategory> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<NoteCategory>()
                        .eq(NoteCategory::getUserId, userId)
                        .orderByAsc(NoteCategory::getSortOrder)
                        .orderByAsc(NoteCategory::getId)
        );

        // 统计每个分类下的记事数
        List<Map<String, Object>> list = new ArrayList<>();
        for (NoteCategory c : categories) {
            Long count = noteMapper.selectCount(
                    new LambdaQueryWrapper<Note>()
                            .eq(Note::getUserId, userId)
                            .eq(Note::getCategoryId, c.getId())
                            .eq(Note::getDeleted, 0)
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("color", c.getColor());
            item.put("sortOrder", c.getSortOrder());
            item.put("noteCount", count);
            list.add(item);
        }

        resp.put("ok", true);
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }

    /** 新建分类 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        if (name.isEmpty()) {
            resp.put("ok", false);
            resp.put("msg", "分类名不能为空");
            return resp;
        }
        if (name.length() > 20) {
            resp.put("ok", false);
            resp.put("msg", "分类名最多20字");
            return resp;
        }
        String color = body.get("color") == null ? "#1a1a1a" : body.get("color").toString().trim();

        // 同名校验
        Long exists = categoryMapper.selectCount(
                new LambdaQueryWrapper<NoteCategory>()
                        .eq(NoteCategory::getUserId, userId)
                        .eq(NoteCategory::getName, name)
        );
        if (exists != null && exists > 0) {
            resp.put("ok", false);
            resp.put("msg", "分类名已存在");
            return resp;
        }

        // sortOrder 取当前最大值+1
        List<NoteCategory> all = categoryMapper.selectList(
                new LambdaQueryWrapper<NoteCategory>()
                        .eq(NoteCategory::getUserId, userId)
                        .orderByDesc(NoteCategory::getSortOrder)
                        .last("LIMIT 1")
        );
        int nextSort = (all.isEmpty() || all.get(0).getSortOrder() == null) ? 0 : all.get(0).getSortOrder() + 1;

        NoteCategory cat = new NoteCategory();
        cat.setUserId(userId);
        cat.setName(name);
        cat.setColor(color);
        cat.setSortOrder(nextSort);
        categoryMapper.insert(cat);

        resp.put("ok", true);
        resp.put("id", cat.getId());
        return resp;
    }

    /** 更新分类 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        NoteCategory cat = categoryMapper.selectById(id);
        if (cat == null || !cat.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "分类不存在");
            return resp;
        }
        String name = body.get("name") == null ? null : body.get("name").toString().trim();
        if (name != null && !name.isEmpty()) {
            // 同名校验（排除自身）
            Long exists = categoryMapper.selectCount(
                    new LambdaQueryWrapper<NoteCategory>()
                            .eq(NoteCategory::getUserId, userId)
                            .eq(NoteCategory::getName, name)
                            .ne(NoteCategory::getId, id)
            );
            if (exists != null && exists > 0) {
                resp.put("ok", false);
                resp.put("msg", "分类名已存在");
                return resp;
            }
            cat.setName(name);
        }
        if (body.get("color") != null) {
            cat.setColor(body.get("color").toString().trim());
        }
        categoryMapper.updateById(cat);

        resp.put("ok", true);
        return resp;
    }

    /** 删除分类（其下记事的 categoryId 置空，不删除记事） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        NoteCategory cat = categoryMapper.selectById(id);
        if (cat == null || !cat.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "分类不存在");
            return resp;
        }

        // 把该分类下的记事的 categoryId 置空
        List<Note> notes = noteMapper.selectList(
                new LambdaQueryWrapper<Note>()
                        .eq(Note::getUserId, userId)
                        .eq(Note::getCategoryId, id)
                        .eq(Note::getDeleted, 0)
        );
        for (Note n : notes) {
            n.setCategoryId(null);
            noteMapper.updateById(n);
        }

        categoryMapper.deleteById(id);
        resp.put("ok", true);
        return resp;
    }
}
