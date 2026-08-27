package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.entity.Note;
import com.code.feishu.mapper.NoteMapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 记事本接口（仿小米记事本）。
 *
 *   GET    /api/note                 查询记事列表（支持 categoryId/starred/keyword 筛选）
 *   POST   /api/note                 新建记事 {"title","content","categoryId"}
 *   GET    /api/note/{id}            查询记事详情
 *   PUT    /api/note/{id}            更新记事 {"title","content","categoryId"}
 *   DELETE /api/note/{id}            删除（软删除，进回收站）
 *   PUT    /api/note/{id}/pin        切换置顶
 *   PUT    /api/note/{id}/star       切换收藏
 *   GET    /api/note/trash           回收站列表
 *   PUT    /api/note/{id}/restore    恢复
 *   GET    /api/note/search?keyword= 搜索记事
 */
@RestController
@RequestMapping("/api/note")
public class NoteController {

    private final NoteMapper noteMapper;

    public NoteController(NoteMapper noteMapper) {
        this.noteMapper = noteMapper;
    }

    /** 查询记事列表（置顶在前，再按更新时间倒序） */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer starred) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        var wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .orderByDesc(Note::getPinned)          // 置顶在前
                .orderByDesc(Note::getUpdateTime);      // 再按更新时间倒序
        if (categoryId != null) {
            wrapper.eq(Note::getCategoryId, categoryId);
        }
        if (starred != null && starred == 1) {
            wrapper.eq(Note::getStarred, 1);
        }

        List<Note> list = noteMapper.selectList(wrapper);
        resp.put("ok", true);
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }

    /** 新建记事 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        String title = body.get("title") == null ? "" : body.get("title").toString().trim();
        String content = body.get("content") == null ? "" : body.get("content").toString().trim();
        if (title.isEmpty() && content.isEmpty()) {
            resp.put("ok", false);
            resp.put("msg", "标题和内容不能同时为空");
            return resp;
        }
        Long categoryId = body.get("categoryId") == null ? null : Long.valueOf(body.get("categoryId").toString());

        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(title);
        note.setContent(content);
        note.setCategoryId(categoryId);
        note.setPinned(body.get("pinned") != null && Integer.valueOf(body.get("pinned").toString()) == 1 ? 1 : 0);
        note.setStarred(body.get("starred") != null && Integer.valueOf(body.get("starred").toString()) == 1 ? 1 : 0);
        note.setDeleted(0);
        noteMapper.insert(note);

        resp.put("ok", true);
        resp.put("id", note.getId());
        return resp;
    }

    /** 查询记事详情 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        resp.put("ok", true);
        resp.put("note", note);
        return resp;
    }

    /** 更新记事 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        if (note.getDeleted() != null && note.getDeleted() == 1) {
            resp.put("ok", false);
            resp.put("msg", "已删除的记事不可编辑，请先恢复");
            return resp;
        }
        String title = body.get("title") == null ? "" : body.get("title").toString().trim();
        String content = body.get("content") == null ? "" : body.get("content").toString().trim();
        if (title.isEmpty() && content.isEmpty()) {
            resp.put("ok", false);
            resp.put("msg", "标题和内容不能同时为空");
            return resp;
        }
        note.setTitle(title);
        note.setContent(content);
        if (body.get("categoryId") != null) {
            note.setCategoryId(body.get("categoryId").toString().isEmpty() ? null : Long.valueOf(body.get("categoryId").toString()));
        }
        if (body.get("pinned") != null) {
            note.setPinned(Integer.valueOf(body.get("pinned").toString()) == 1 ? 1 : 0);
        }
        if (body.get("starred") != null) {
            note.setStarred(Integer.valueOf(body.get("starred").toString()) == 1 ? 1 : 0);
        }
        note.setUpdateTime(LocalDateTime.now());
        noteMapper.updateById(note);

        resp.put("ok", true);
        return resp;
    }

    /** 删除 —— 软删除（移到回收站） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        note.setDeleted(1);
        note.setPinned(0);   // 删除时取消置顶
        note.setUpdateTime(LocalDateTime.now());
        noteMapper.updateById(note);
        resp.put("ok", true);
        return resp;
    }

    /** 切换置顶 */
    @PutMapping("/{id}/pin")
    public Map<String, Object> togglePin(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        int newPinned = (note.getPinned() != null && note.getPinned() == 1) ? 0 : 1;
        note.setPinned(newPinned);
        noteMapper.updateById(note);
        resp.put("ok", true);
        resp.put("pinned", newPinned);
        return resp;
    }

    /** 切换收藏 */
    @PutMapping("/{id}/star")
    public Map<String, Object> toggleStar(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        int newStarred = (note.getStarred() != null && note.getStarred() == 1) ? 0 : 1;
        note.setStarred(newStarred);
        noteMapper.updateById(note);
        resp.put("ok", true);
        resp.put("starred", newStarred);
        return resp;
    }

    /** 回收站列表 */
    @GetMapping("/trash")
    public Map<String, Object> trashList() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        List<Note> list = noteMapper.selectList(
                new LambdaQueryWrapper<Note>()
                        .eq(Note::getUserId, userId)
                        .eq(Note::getDeleted, 1)
                        .orderByDesc(Note::getUpdateTime)
                        .last("LIMIT 500")
        );
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }

    /** 恢复记事 */
    @PutMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "记事不存在");
            return resp;
        }
        note.setDeleted(0);
        note.setUpdateTime(LocalDateTime.now());
        noteMapper.updateById(note);
        resp.put("ok", true);
        return resp;
    }

    /** 搜索记事（标题 + 内容） */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer starred,
            @RequestParam(defaultValue = "200") int limit) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        if (limit <= 0 || limit > 500) limit = 200;

        var wrapper = new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .orderByDesc(Note::getPinned)
                .orderByDesc(Note::getUpdateTime)
                .last("LIMIT " + limit);

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Note::getTitle, kw).or().like(Note::getContent, kw));
        }
        if (categoryId != null) {
            wrapper.eq(Note::getCategoryId, categoryId);
        }
        if (starred != null && starred == 1) {
            wrapper.eq(Note::getStarred, 1);
        }

        List<Note> list = noteMapper.selectList(wrapper);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }
}
