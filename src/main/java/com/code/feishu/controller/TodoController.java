package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.entity.Todo;
import com.code.feishu.mapper.TodoMapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 待办事项接口（仿小米记事本待办）。
 *
 *   GET    /api/todo              查询当前用户所有待办（分 pending/done 两组）
 *   POST   /api/todo              新建待办 {"content":"xxx"}
 *   PUT    /api/todo/{id}/toggle  切换完成状态（未完成↔已完成）
 *   PUT    /api/todo/{id}         编辑内容 {"content":"xxx"}
 *   DELETE /api/todo/{id}         删除
 */
@RestController
@RequestMapping("/api/todo")
public class TodoController {

    private final TodoMapper todoMapper;

    public TodoController(TodoMapper todoMapper) {
        this.todoMapper = todoMapper;
    }

    /** 查询所有待办，按 pending / done 分组返回 */
    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        List<Todo> all = todoMapper.selectList(
                new LambdaQueryWrapper<Todo>()
                        .eq(Todo::getUserId, userId)
                        .orderByAsc(Todo::getCompleted)         // 未完成在前
                        .orderByAsc(Todo::getSortOrder)         // 同状态按 sortOrder
                        .orderByDesc(Todo::getCreateTime)       // 同 sortOrder 按创建时间倒序
        );

        List<Todo> pending = new ArrayList<>();
        List<Todo> done = new ArrayList<>();
        for (Todo t : all) {
            if (t.getCompleted() != null && t.getCompleted() == 1) {
                done.add(t);
            } else {
                pending.add(t);
            }
        }

        resp.put("ok", true);
        resp.put("pending", pending);
        resp.put("done", done);
        return resp;
    }

    /** 新建待办 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            resp.put("ok", false);
            resp.put("msg", "内容不能为空");
            return resp;
        }
        if (content.length() > 500) {
            resp.put("ok", false);
            resp.put("msg", "内容最多500字");
            return resp;
        }

        Todo todo = new Todo();
        todo.setUserId(userId);
        todo.setContent(content.trim());
        todo.setCompleted(0);
        todo.setSortOrder(0);
        todoMapper.insert(todo);

        resp.put("ok", true);
        resp.put("id", todo.getId());
        return resp;
    }

    /** 切换完成状态（点击圆圈） */
    @PutMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        Todo todo = todoMapper.selectById(id);
        if (todo == null || !todo.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "待办不存在");
            return resp;
        }

        int newStatus = (todo.getCompleted() != null && todo.getCompleted() == 1) ? 0 : 1;
        todo.setCompleted(newStatus);
        todo.setCompletedAt(newStatus == 1 ? LocalDateTime.now() : null);
        todoMapper.updateById(todo);

        resp.put("ok", true);
        resp.put("completed", newStatus);
        return resp;
    }

    /** 编辑内容 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        Todo todo = todoMapper.selectById(id);
        if (todo == null || !todo.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "待办不存在");
            return resp;
        }

        String content = body.get("content");
        if (content == null || content.isBlank()) {
            resp.put("ok", false);
            resp.put("msg", "内容不能为空");
            return resp;
        }

        todo.setContent(content.trim());
        todoMapper.updateById(todo);

        resp.put("ok", true);
        return resp;
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (userId == null) {
            resp.put("ok", false);
            resp.put("msg", "未登录");
            return resp;
        }

        Todo todo = todoMapper.selectById(id);
        if (todo == null || !todo.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "待办不存在");
            return resp;
        }

        todoMapper.deleteById(id);
        resp.put("ok", true);
        return resp;
    }
}
