package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.entity.DiaryPublishConfig;
import com.code.feishu.mapper.DiaryPublishConfigMapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日记发布配置接口。
 *
 * GET /api/diary/publish-config        查询当前用户的发布配置（不存在则返回默认值 1:00 启用）
 * PUT /api/diary/publish-config        更新发布配置
 *     参数：{"publishHour": 1, "enabled": 1}  publishHour 范围 0-23
 */
@RestController
@RequestMapping("/api/diary/publish-config")
public class DiaryPublishConfigController {

    private final DiaryPublishConfigMapper configMapper;

    public DiaryPublishConfigController(DiaryPublishConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    /**
     * 查询当前用户的发布配置。
     * 如果用户还没配置过，返回默认值（publishHour=1, enabled=1）。
     */
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();

        DiaryPublishConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<DiaryPublishConfig>()
                        .eq(DiaryPublishConfig::getUserId, userId)
        );

        // 没配置过 → 返回默认值
        if (config == null) {
            resp.put("ok", true);
            resp.put("publishHour", 1);
            resp.put("enabled", 1);
            resp.put("isDefault", true);
            return resp;
        }

        resp.put("ok", true);
        resp.put("publishHour", config.getPublishHour());
        resp.put("enabled", config.getEnabled());
        resp.put("isDefault", false);
        return resp;
    }

    /**
     * 更新发布配置（不存在则新增，存在则更新）。
     */
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();

        // 参数校验
        Object hourObj = body.get("publishHour");
        Object enabledObj = body.get("enabled");
        if (hourObj == null) {
            resp.put("ok", false);
            resp.put("msg", "publishHour 不能为空");
            return resp;
        }
        int publishHour;
        try {
            publishHour = Integer.parseInt(String.valueOf(hourObj));
        } catch (NumberFormatException e) {
            resp.put("ok", false);
            resp.put("msg", "publishHour 必须是整数");
            return resp;
        }
        if (publishHour < 0 || publishHour > 23) {
            resp.put("ok", false);
            resp.put("msg", "publishHour 必须在 0-23 之间");
            return resp;
        }
        int enabled = 1;
        if (enabledObj != null) {
            enabled = Integer.parseInt(String.valueOf(enabledObj));
            if (enabled != 0 && enabled != 1) {
                resp.put("ok", false);
                resp.put("msg", "enabled 必须是 0 或 1");
                return resp;
            }
        }

        // 不存在则新增，存在则更新
        DiaryPublishConfig existing = configMapper.selectOne(
                new LambdaQueryWrapper<DiaryPublishConfig>()
                        .eq(DiaryPublishConfig::getUserId, userId)
        );

        if (existing == null) {
            DiaryPublishConfig newConfig = new DiaryPublishConfig();
            newConfig.setUserId(userId);
            newConfig.setPublishHour(publishHour);
            newConfig.setEnabled(enabled);
            configMapper.insert(newConfig);
        } else {
            existing.setPublishHour(publishHour);
            existing.setEnabled(enabled);
            configMapper.updateById(existing);
        }

        resp.put("ok", true);
        resp.put("msg", "配置已保存");
        resp.put("publishHour", publishHour);
        resp.put("enabled", enabled);
        return resp;
    }
}
