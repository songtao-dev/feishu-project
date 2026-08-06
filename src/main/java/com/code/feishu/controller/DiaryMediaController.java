package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.controller.DiaryController;
import com.code.feishu.entity.Diary;
import com.code.feishu.entity.DiaryMedia;
import com.code.feishu.mapper.DiaryMapper;
import com.code.feishu.mapper.DiaryMediaMapper;
import com.code.feishu.service.OssService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 日记媒体接口（图片/语音上传到 OSS）。
 *
 * 接口列表：
 *   POST   /api/diary/{diaryId}/media/image   上传图片（支持多张）
 *   POST   /api/diary/{diaryId}/media/voice   上传语音（单个）
 *   GET    /api/diary/{diaryId}/media         查询某篇日记的媒体列表
 *   DELETE /api/diary/media/{id}              删除单个媒体
 *
 * 权限：必须是该日记的作者才能上传/删除媒体；已发布日记不可操作媒体。
 */
@RestController
@RequestMapping("/api/diary")
public class DiaryMediaController {

    /** 媒体类型常量 */
    private static final int TYPE_IMAGE = 1;
    private static final int TYPE_VOICE = 2;

    /** 图片限制 */
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024;        // 单张 5MB
    private static final int MAX_IMAGE_COUNT = 9;                       // 单次最多 9 张
    /** 语音限制 */
    private static final long MAX_VOICE_SIZE = 10 * 1024 * 1024;       // 单条 10MB

    private final DiaryMapper diaryMapper;
    private final DiaryMediaMapper mediaMapper;
    private final OssService ossService;

    public DiaryMediaController(DiaryMapper diaryMapper,
                                DiaryMediaMapper mediaMapper,
                                OssService ossService) {
        this.diaryMapper = diaryMapper;
        this.mediaMapper = mediaMapper;
        this.ossService = ossService;
    }

    /** 上传图片（支持多张，字段名 file） */
    @PostMapping("/{diaryId}/media/image")
    public Map<String, Object> uploadImages(@PathVariable Long diaryId,
                                            @RequestParam("file") MultipartFile[] files) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();

        // 校验日记
        String authErr = assertDiaryOwner(diaryId, userId);
        if (authErr != null) {
            resp.put("ok", false);
            resp.put("msg", authErr);
            return resp;
        }

        if (files == null || files.length == 0) {
            resp.put("ok", false);
            resp.put("msg", "请选择图片");
            return resp;
        }
        if (files.length > MAX_IMAGE_COUNT) {
            resp.put("ok", false);
            resp.put("msg", "单次最多上传 " + MAX_IMAGE_COUNT + " 张图片");
            return resp;
        }

        // 当前最大 sortOrder
        int nextSort = getMaxSortOrder(diaryId);

        List<Map<String, Object>> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            if (file.getSize() > MAX_IMAGE_SIZE) {
                resp.put("ok", false);
                resp.put("msg", "图片 " + file.getOriginalFilename() + " 超过5MB限制");
                return resp;
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                resp.put("ok", false);
                resp.put("msg", "仅支持图片格式");
                return resp;
            }

            try {
                String url = ossService.upload(file, "image");
                DiaryMedia media = new DiaryMedia();
                media.setDiaryId(diaryId);
                media.setUserId(userId);
                media.setType(TYPE_IMAGE);
                media.setUrl(url);
                media.setMime(contentType);
                media.setSize(file.getSize());
                media.setDuration(0);
                media.setSortOrder(++nextSort);
                media.setDeleted(0);
                mediaMapper.insert(media);

                uploaded.add(mediaToMap(media));
            } catch (Exception e) {
                resp.put("ok", false);
                resp.put("msg", "上传失败：" + e.getMessage());
                return resp;
            }
        }

        resp.put("ok", true);
        resp.put("msg", "上传成功");
        resp.put("media", uploaded);
        return resp;
    }

    /** 上传语音（单个，字段名 file，可选 duration 秒） */
    @PostMapping("/{diaryId}/media/voice")
    public Map<String, Object> uploadVoice(@PathVariable Long diaryId,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "duration", required = false, defaultValue = "0") Integer duration) {
        Map<String, Object> resp = new LinkedHashMap<>();

        Long userId = UserContext.getUserId();

        String authErr = assertDiaryOwner(diaryId, userId);
        if (authErr != null) {
            resp.put("ok", false);
            resp.put("msg", authErr);
            return resp;
        }

        if (file == null || file.isEmpty()) {
            resp.put("ok", false);
            resp.put("msg", "请选择语音文件");
            return resp;
        }
        if (file.getSize() > MAX_VOICE_SIZE) {
            resp.put("ok", false);
            resp.put("msg", "语音文件超过10MB限制");
            return resp;
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            resp.put("ok", false);
            resp.put("msg", "仅支持音频格式");
            return resp;
        }

        try {
            String url = ossService.upload(file, "voice");
            DiaryMedia media = new DiaryMedia();
            media.setDiaryId(diaryId);
            media.setUserId(userId);
            media.setType(TYPE_VOICE);
            media.setUrl(url);
            media.setMime(contentType);
            media.setSize(file.getSize());
            media.setDuration(duration != null ? duration : 0);
            media.setSortOrder(getMaxSortOrder(diaryId) + 1);
            media.setDeleted(0);
            mediaMapper.insert(media);

            resp.put("ok", true);
            resp.put("msg", "上传成功");
            resp.put("media", mediaToMap(media));
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("msg", "上传失败：" + e.getMessage());
        }
        return resp;
    }

    /** 查询某篇日记的媒体列表（按 sortOrder 升序） */
    @GetMapping("/{diaryId}/media")
    public Map<String, Object> listMedia(@PathVariable Long diaryId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        LambdaQueryWrapper<DiaryMedia> qw = new LambdaQueryWrapper<>();
        qw.eq(DiaryMedia::getDiaryId, diaryId)
          .eq(DiaryMedia::getDeleted, 0)
          .orderByAsc(DiaryMedia::getSortOrder);
        List<DiaryMedia> list = mediaMapper.selectList(qw);
        List<Map<String, Object>> mediaList = new ArrayList<>();
        for (DiaryMedia m : list) {
            mediaList.add(mediaToMap(m));
        }
        resp.put("ok", true);
        resp.put("media", mediaList);
        return resp;
    }

    /** 删除单个媒体（软删除） */
    @DeleteMapping("/media/{id}")
    public Map<String, Object> deleteMedia(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        DiaryMedia media = mediaMapper.selectById(id);
        if (media == null || media.getDeleted() == 1) {
            resp.put("ok", false);
            resp.put("msg", "媒体不存在");
            return resp;
        }
        // 校验权限：必须是上传人 且 日记未发布
        if (!media.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "无权删除");
            return resp;
        }
        Diary diary = diaryMapper.selectById(media.getDiaryId());
        if (diary != null && DiaryController.STATUS_PUBLISHED.equals(diary.getStatus())) {
            resp.put("ok", false);
            resp.put("msg", "已发布的日记不可修改");
            return resp;
        }

        media.setDeleted(1);
        mediaMapper.updateById(media);
        // 异步删除 OSS 文件（不阻塞响应）
        ossService.delete(media.getUrl());

        resp.put("ok", true);
        resp.put("msg", "已删除");
        return resp;
    }

    // ===== 内部工具方法 =====

    /**
     * 校验当前用户是日记作者 且 日记未发布。
     * 返回 null=通过，否则返回错误信息。
     */
    private String assertDiaryOwner(Long diaryId, Long userId) {
        Diary diary = diaryMapper.selectById(diaryId);
        if (diary == null || diary.getDeleted() == 1) {
            return "日记不存在";
        }
        if (DiaryController.STATUS_PUBLISHED.equals(diary.getStatus())) {
            return "已发布的日记不可修改";
        }
        // 私人日记：userId 匹配；共享日记：authorUserId 匹配
        Long ownerId = diary.getGroupId() == null ? diary.getUserId() : diary.getAuthorUserId();
        if (ownerId == null || !ownerId.equals(userId)) {
            return "无权操作此日记";
        }
        return null;
    }

    /** 获取当前最大 sortOrder */
    private int getMaxSortOrder(Long diaryId) {
        LambdaQueryWrapper<DiaryMedia> qw = new LambdaQueryWrapper<>();
        qw.eq(DiaryMedia::getDiaryId, diaryId)
          .eq(DiaryMedia::getDeleted, 0)
          .orderByDesc(DiaryMedia::getSortOrder)
          .last("LIMIT 1");
        DiaryMedia last = mediaMapper.selectOne(qw);
        return last != null && last.getSortOrder() != null ? last.getSortOrder() : 0;
    }

    /** 媒体实体转 Map */
    private Map<String, Object> mediaToMap(DiaryMedia m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("diaryId", m.getDiaryId());
        map.put("type", m.getType());
        map.put("url", m.getUrl());
        map.put("mime", m.getMime());
        map.put("size", m.getSize());
        map.put("duration", m.getDuration());
        map.put("sortOrder", m.getSortOrder());
        map.put("createTime", m.getCreateTime());
        return map;
    }
}
