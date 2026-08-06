package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.dto.DiaryDTO;
import com.code.feishu.entity.Diary;
import com.code.feishu.entity.DiaryGroup;
import com.code.feishu.entity.DiaryGroupMember;
import com.code.feishu.entity.DiaryMedia;
import com.code.feishu.mapper.DiaryGroupMapper;
import com.code.feishu.mapper.DiaryGroupMemberMapper;
import com.code.feishu.mapper.DiaryMapper;
import com.code.feishu.mapper.DiaryMediaMapper;
import com.code.feishu.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 随笔日记接口。
 *
 *   POST   /api/diary                 新建日记
 *   GET    /api/diary/{id}            查询单篇日记（详情/编辑回显）
 *   PUT    /api/diary/{id}            更新日记（只更传了字段）
 *   DELETE /api/diary/{id}            删除日记
 *   GET    /api/diary/monthly         月度时间轴（返回该月所有日记，含正文前15字预览）
 *
 * 心情分列规则（前端时间轴左侧 vs 右侧）：
 *   左侧（坏心情）：bad / very_bad
 *   右侧（其余）：very_happy / happy / ok / emo
 */
@RestController
@RequestMapping("/api/diary")
public class DiaryController {

    /** 心情 -> emoji 映射（后端只存英文 key，前端也有一份映射，这里用于月度接口返回方便前端直接显示） */
    private static final Map<String, String> MOOD_EMOJI = new LinkedHashMap<>();
    /** 心情 -> 中文名映射 */
    private static final Map<String, String> MOOD_NAME = new LinkedHashMap<>();
    /** 天气 -> emoji 映射 */
    private static final Map<String, String> WEATHER_EMOJI = new LinkedHashMap<>();
    static {
        MOOD_EMOJI.put("very_happy", "😄");
        MOOD_EMOJI.put("happy",      "🙂");
        MOOD_EMOJI.put("ok",         "😐");
        MOOD_EMOJI.put("emo",        "😔");
        MOOD_EMOJI.put("bad",        "☹️");
        MOOD_EMOJI.put("very_bad",   "😢");

        MOOD_NAME.put("very_happy", "非常开心");
        MOOD_NAME.put("happy",      "开心");
        MOOD_NAME.put("ok",         "不错");
        MOOD_NAME.put("emo",        "有点emo");
        MOOD_NAME.put("bad",        "有点糟糕");
        MOOD_NAME.put("very_bad",   "很糟糕");

        WEATHER_EMOJI.put("sunny",  "☀️");
        WEATHER_EMOJI.put("cloudy", "☁️");
        WEATHER_EMOJI.put("rainy",  "🌧️");
        WEATHER_EMOJI.put("snowy",  "❄️");
        WEATHER_EMOJI.put("windy",  "💨");
        WEATHER_EMOJI.put("foggy",  "🌫️");
    }

    /** 预览正文长度（按码点截，避免 emoji 代理对被截断） */
    private static final int PREVIEW_LEN = 15;

    /** 日记状态常量 */
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";

    private final DiaryMapper diaryMapper;
    private final DiaryGroupMapper groupMapper;
    private final DiaryGroupMemberMapper memberMapper;
    private final UserService userService;
    private final DiaryMediaMapper mediaMapper;

    public DiaryController(DiaryMapper diaryMapper,
                           DiaryGroupMapper groupMapper,
                           DiaryGroupMemberMapper memberMapper,
                           UserService userService,
                           DiaryMediaMapper mediaMapper) {
        this.diaryMapper = diaryMapper;
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.userService = userService;
        this.mediaMapper = mediaMapper;
    }

    // ==================== 新建 ====================

    /**
     * 新建日记。
     * 请求：{"title":"今天不错","content":"正文...","mood":"happy",
     *       "weather":"sunny","tags":"生活,工作","diaryDate":"2025-04-15"}
     * 成功：{"ok":true,"id":12}
     * 失败：{"ok":false,"msg":"正文不能为空"}
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody DiaryDTO dto) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();

        // 校验：正文必填
        if (dto == null || dto.getContent() == null || dto.getContent().isBlank()) {
            resp.put("ok", false);
            resp.put("msg", "正文不能为空");
            return resp;
        }

        // 若指定 groupId → 共享日记本日记：校验当前用户是该组 active 成员 + 上限
        Long groupId = dto.getGroupId();
        if (groupId != null) {
            String authErr = assertActiveMember(groupId, userId);
            if (authErr != null) {
                resp.put("ok", false);
                resp.put("msg", authErr);
                return resp;
            }
        }

        Diary diary = new Diary();
        diary.setUserId(userId);                 // 私人日记用；共享日记里冗余存作者
        diary.setGroupId(groupId);               // null=私人日记
        diary.setAuthorUserId(userId);           // 共享日记作者，便于权限校验
        diary.setStatus(STATUS_DRAFT);           // 新建默认为草稿状态
        diary.setTitle(dto.getTitle());
        diary.setContent(dto.getContent());
        diary.setMood(dto.getMood());
        diary.setWeather(dto.getWeather());
        diary.setTags(dto.getTags());
        // 日期不传默认今天
        diary.setDiaryDate(parseDate(dto.getDiaryDate(), LocalDate.now()));

        diaryMapper.insert(diary);

        resp.put("ok", true);
        resp.put("id", diary.getId());
        resp.put("msg", "创建成功");
        return resp;
    }

    // ==================== 查询单篇 ====================

    /**
     * 查询单篇日记（详情/编辑回显）。
     * 成功：{"ok":true,"diary":{...}}
     * 失败：{"ok":false,"msg":"日记不存在"}
     */
    @GetMapping("/{id}")
    public Map<String, Object> getById(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Diary diary = diaryMapper.selectById(id);
        if (diary == null) {
            resp.put("ok", false);
            resp.put("msg", "日记不存在，id=" + id);
            return resp;
        }
        Long userId = UserContext.getUserId();
        // 权限校验：
        //   - 私人日记（groupId=null）：只能本人查
        //   - 共享日记（groupId≠null）：该组 active 成员都能查
        if (diary.getGroupId() == null) {
            if (diary.getUserId() == null || !diary.getUserId().equals(userId)) {
                resp.put("ok", false);
                resp.put("msg", "无权查看此日记");
                return resp;
            }
        } else {
            String authErr = assertActiveMember(diary.getGroupId(), userId);
            if (authErr != null) {
                resp.put("ok", false);
                resp.put("msg", authErr);
                return resp;
            }
        }

        Map<String, Object> detail = buildDiaryDetail(diary);
        detail.put("groupId", diary.getGroupId());
        detail.put("authorUserId", diary.getAuthorUserId());
        detail.put("authorName",
                diary.getAuthorUserId() == null ? "" : userService.getDisplayName(diary.getAuthorUserId()));
        detail.put("isMine", Objects.equals(
                diary.getGroupId() == null ? diary.getUserId() : diary.getAuthorUserId(), userId));

        resp.put("ok", true);
        resp.put("diary", detail);
        return resp;
    }

    // ==================== 更新 ====================

    /**
     * 更新日记（只更传了字段）。
     * 请求：{"title":"新标题","content":"新正文",...}
     * 成功：{"ok":true,"msg":"更新成功"}
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Diary diary = diaryMapper.selectById(id);
        if (diary == null) {
            resp.put("ok", false);
            resp.put("msg", "日记不存在，id=" + id);
            return resp;
        }
        // 权限校验：只能改自己写的日记
        //   - 私人日记：diary.userId == 当前用户
        //   - 共享日记：diary.authorUserId == 当前用户（组主也不能改别人的）
        Long userId = UserContext.getUserId();
        String authErr = assertAuthor(diary, userId);
        if (authErr != null) {
            resp.put("ok", false);
            resp.put("msg", authErr);
            return resp;
        }

        // 只更新前端传了的字段
        if (body.containsKey("title"))      diary.setTitle((String) body.get("title"));
        if (body.containsKey("content"))    diary.setContent((String) body.get("content"));
        if (body.containsKey("mood"))       diary.setMood((String) body.get("mood"));
        if (body.containsKey("weather"))    diary.setWeather((String) body.get("weather"));
        if (body.containsKey("tags"))       diary.setTags((String) body.get("tags"));
        if (body.containsKey("diaryDate"))  diary.setDiaryDate(parseDate((String) body.get("diaryDate"), diary.getDiaryDate()));

        diaryMapper.updateById(diary);

        resp.put("ok", true);
        resp.put("msg", "更新成功");
        resp.put("diary", buildDiaryDetail(diary));
        return resp;
    }

    // ==================== 删除 ====================

    /**
     * 删除日记。
     * 成功：{"ok":true,"msg":"删除成功"}
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Diary diary = diaryMapper.selectById(id);
        if (diary == null) {
            resp.put("ok", false);
            resp.put("msg", "日记不存在，id=" + id);
            return resp;
        }
        // 权限校验：只能删自己写的日记（组主也不能删别人的）
        Long userId = UserContext.getUserId();
        String authErr = assertAuthor(diary, userId);
        if (authErr != null) {
            resp.put("ok", false);
            resp.put("msg", authErr);
            return resp;
        }

        diaryMapper.deleteById(id);
        resp.put("ok", true);
        resp.put("msg", "删除成功");
        return resp;
    }

    // ==================== 发布 ====================

    /**
     * 发布日记（草稿 → 已发布，发布后不可修改/删除）。
     * 只能由作者本人发布自己的日记。
     * 成功：{"ok":true,"msg":"发布成功"}
     */
    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Diary diary = diaryMapper.selectById(id);
        if (diary == null) {
            resp.put("ok", false);
            resp.put("msg", "日记不存在");
            return resp;
        }
        Long userId = UserContext.getUserId();
        String authErr = assertAuthor(diary, userId);
        if (authErr != null && !"已发布的日记不可修改或删除".equals(authErr)) {
            resp.put("ok", false);
            resp.put("msg", authErr);
            return resp;
        }
        // 已发布直接返回成功（幂等）
        if (STATUS_PUBLISHED.equals(diary.getStatus())) {
            resp.put("ok", true);
            resp.put("msg", "已是发布状态");
            return resp;
        }
        // 校验作者身份后发布
        if (diary.getGroupId() == null) {
            if (!Objects.equals(diary.getUserId(), userId)) {
                resp.put("ok", false);
                resp.put("msg", "无权发布此日记");
                return resp;
            }
        } else {
            if (!Objects.equals(diary.getAuthorUserId(), userId)) {
                resp.put("ok", false);
                resp.put("msg", "只能发布自己写的日记");
                return resp;
            }
        }
        diary.setStatus(STATUS_PUBLISHED);
        diaryMapper.updateById(diary);
        resp.put("ok", true);
        resp.put("msg", "发布成功");
        resp.put("diary", buildDiaryDetail(diary));
        return resp;
    }

    // ==================== 月度时间轴 ====================

    /**
     * 月度时间轴：返回指定年月所有日记，含正文前15字预览。
     *
     * 用法：GET /api/diary/monthly?year=2025&month=4
     * 默认：不传参数返回当前年月
     *
     * 返回结构：
     *   {
     *     "ok": true,
     *     "year": 2025, "month": 4, "daysInMonth": 30,
     *     "total": 8,
     *     "diaries": [
     *       { "id":1, "diaryDate":"2025-04-15", "title":"...", "preview":"正文前15字",
     *         "mood":"happy", "moodEmoji":"🙂", "moodName":"开心",
     *         "weather":"sunny", "weatherEmoji":"☀️", "tags":["生活","工作"] }
     *     ]
     *   }
     *
     * 排序：按 diary_date 降序（最新在前），前端可自行反转成正序
     */
    @GetMapping("/monthly")
    public Map<String, Object> monthly(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        Long userId = UserContext.getUserId();
        LocalDate today = LocalDate.now();
        if (year == null)  year = today.getYear();
        if (month == null) month = today.getMonthValue();

        // 计算月份起止日期
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Diary> list = diaryMapper.selectList(
                new LambdaQueryWrapper<Diary>()
                        .eq(Diary::getUserId, userId)
                        .isNull(Diary::getGroupId)   // 仅私人日记；共享日记走 /api/diary-group/{id}/monthly
                        .ge(Diary::getDiaryDate, start)
                        .le(Diary::getDiaryDate, end)
                        .orderByDesc(Diary::getDiaryDate)
        );

        List<Map<String, Object>> diaries = new ArrayList<>();
        for (Diary d : list) {
            diaries.add(buildDiaryPreview(d));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("year", year);
        resp.put("month", month);
        resp.put("daysInMonth", ym.lengthOfMonth());
        resp.put("total", list.size());
        resp.put("diaries", diaries);
        return resp;
    }

    // ==================== 工具方法 ====================

    /**
     * 校验当前用户是该日记的作者（改/删权限）。
     *   - 私人日记：userId == 当前用户
     *   - 共享日记：authorUserId == 当前用户（组主也不能改删别人的）
     *   - 已发布(published)状态：禁止修改和删除
     * 返回 null=通过，否则返回错误信息。
     */
    private String assertAuthor(Diary diary, Long userId) {
        // 已发布日记不能修改/删除
        if (STATUS_PUBLISHED.equals(diary.getStatus())) {
            return "已发布的日记不可修改或删除";
        }
        if (diary.getGroupId() == null) {
            if (diary.getUserId() == null || !diary.getUserId().equals(userId)) {
                return "无权操作此日记";
            }
            return null;
        }
        if (diary.getAuthorUserId() == null || !diary.getAuthorUserId().equals(userId)) {
            return "只能修改/删除自己写的日记";
        }
        return null;
    }

    /** 校验当前用户是某组的 active 成员，返回 null=通过 */
    private String assertActiveMember(Long groupId, Long userId) {
        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, userId)
        );
        if (m == null || "left".equals(m.getStatus())) return "你不在该日记本中";
        if ("pending".equals(m.getStatus())) return "邀请/申请待确认，暂不可访问";
        return null;
    }

    /** 解析日期字符串，失败返回默认值 */
    private LocalDate parseDate(String dateStr, LocalDate defaultValue) {
        if (dateStr == null || dateStr.isBlank()) return defaultValue;
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 构建日记详情返回对象（含完整字段 + 媒体列表） */
    private Map<String, Object> buildDiaryDetail(Diary d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", d.getId());
        item.put("title", d.getTitle());
        item.put("content", d.getContent());
        item.put("mood", d.getMood());
        item.put("moodEmoji", MOOD_EMOJI.getOrDefault(d.getMood(), ""));
        item.put("moodName", MOOD_NAME.getOrDefault(d.getMood(), ""));
        item.put("weather", d.getWeather());
        item.put("weatherEmoji", WEATHER_EMOJI.getOrDefault(d.getWeather(), ""));
        item.put("tags", parseTags(d.getTags()));
        item.put("status", d.getStatus() != null ? d.getStatus() : STATUS_DRAFT);
        item.put("diaryDate", d.getDiaryDate() != null ? d.getDiaryDate().toString() : null);
        item.put("createTime", d.getCreateTime());
        item.put("updateTime", d.getUpdateTime());
        item.put("media", queryMediaList(d.getId()));
        return item;
    }

    /** 构建日记预览返回对象（列表用，正文只取前15字 + 媒体计数） */
    private Map<String, Object> buildDiaryPreview(Diary d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", d.getId());
        item.put("diaryDate", d.getDiaryDate() != null ? d.getDiaryDate().toString() : null);
        item.put("title", d.getTitle());
        // 按码点截取，避免 emoji 代理对被截半（如 😄 被截成 ?）
        item.put("preview", makePreview(d.getContent()));
        item.put("mood", d.getMood());
        item.put("moodEmoji", MOOD_EMOJI.getOrDefault(d.getMood(), ""));
        item.put("moodName", MOOD_NAME.getOrDefault(d.getMood(), ""));
        item.put("weather", d.getWeather());
        item.put("weatherEmoji", WEATHER_EMOJI.getOrDefault(d.getWeather(), ""));
        item.put("tags", parseTags(d.getTags()));
        item.put("status", d.getStatus() != null ? d.getStatus() : STATUS_DRAFT);
        // 媒体计数（前端列表卡片显示"有图/有语音"标识用）
        int[] counts = queryMediaCounts(d.getId());
        item.put("imageCount", counts[0]);
        item.put("voiceCount", counts[1]);
        // 第一张图URL（列表卡片缩略图用，没有则null）
        item.put("coverImage", queryCoverImage(d.getId()));
        return item;
    }

    /** 查询某篇日记的媒体列表（按 sortOrder 升序，仅未删除） */
    private List<Map<String, Object>> queryMediaList(Long diaryId) {
        LambdaQueryWrapper<DiaryMedia> qw = new LambdaQueryWrapper<>();
        qw.eq(DiaryMedia::getDiaryId, diaryId)
          .eq(DiaryMedia::getDeleted, 0)
          .orderByAsc(DiaryMedia::getSortOrder);
        List<DiaryMedia> list = mediaMapper.selectList(qw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DiaryMedia m : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("type", m.getType());
            map.put("url", m.getUrl());
            map.put("mime", m.getMime());
            map.put("duration", m.getDuration());
            map.put("sortOrder", m.getSortOrder());
            result.add(map);
        }
        return result;
    }

    /** 查询媒体计数 [图片数, 语音数] */
    private int[] queryMediaCounts(Long diaryId) {
        LambdaQueryWrapper<DiaryMedia> qw = new LambdaQueryWrapper<>();
        qw.eq(DiaryMedia::getDiaryId, diaryId).eq(DiaryMedia::getDeleted, 0);
        List<DiaryMedia> list = mediaMapper.selectList(qw);
        int imgCount = 0, voiceCount = 0;
        for (DiaryMedia m : list) {
            if (m.getType() != null && m.getType() == 1) imgCount++;
            else if (m.getType() != null && m.getType() == 2) voiceCount++;
        }
        return new int[]{imgCount, voiceCount};
    }

    /** 查询第一张图片URL（作为列表卡片封面） */
    private String queryCoverImage(Long diaryId) {
        LambdaQueryWrapper<DiaryMedia> qw = new LambdaQueryWrapper<>();
        qw.eq(DiaryMedia::getDiaryId, diaryId)
          .eq(DiaryMedia::getDeleted, 0)
          .eq(DiaryMedia::getType, 1)
          .orderByAsc(DiaryMedia::getSortOrder)
          .last("LIMIT 1");
        DiaryMedia cover = mediaMapper.selectOne(qw);
        return cover != null ? cover.getUrl() : null;
    }

    /** 构建日记列表项（搜索/回收站用，含作者信息和 isMine 标记） */
    private Map<String, Object> toListItem(Diary d, Long currentUserId, UserService userService) {
        Map<String, Object> item = buildDiaryPreview(d);
        item.put("createTime", d.getCreateTime());
        item.put("updateTime", d.getUpdateTime());
        item.put("authorUserId", d.getAuthorUserId());
        item.put("authorName",
                d.getAuthorUserId() == null ? "" : userService.getDisplayName(d.getAuthorUserId()));
        item.put("isMine", Objects.equals(
                d.getGroupId() == null ? d.getUserId() : d.getAuthorUserId(), currentUserId));
        return item;
    }

    /** 按码点截取前 N 字，超出加省略号 */
    private String makePreview(String content) {
        if (content == null) return "";
        String[] parts = content.split("\\s+", 2);
        String text = parts[0];
        int[] cps = text.codePoints().toArray();
        if (cps.length <= PREVIEW_LEN) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PREVIEW_LEN; i++) sb.appendCodePoint(cps[i]);
        sb.append("...");
        return sb.toString();
    }

    /** 把 tags 字符串拆成数组（空值返回空数组） */
    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return Collections.emptyList();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    // ============================================================
    // 日记搜索/筛选
    // ============================================================

    /**
     * 搜索日记。
     * GET /api/diary/search?keyword=开心&mood=happy&weather=sunny&tag=工作&start=2025-08-01&end=2025-08-31&limit=100
     */
    @GetMapping("/search")
    public Map<String, Object> searchDiary(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mood,
            @RequestParam(required = false) String weather,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "100") int limit) {
        Long userId = UserContext.getUserId();
        if (limit <= 0 || limit > 500) limit = 100;

        var wrapper = new LambdaQueryWrapper<Diary>()
                .eq(Diary::getUserId, userId)
                .eq(Diary::getDeleted, 0)
                .orderByDesc(Diary::getDiaryDate)
                .last("LIMIT " + limit);

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Diary::getTitle, kw).or().like(Diary::getContent, kw));
        }
        if (mood != null && !mood.isBlank() && !"全部".equals(mood)) wrapper.eq(Diary::getMood, mood);
        if (weather != null && !weather.isBlank() && !"全部".equals(weather)) wrapper.eq(Diary::getWeather, weather);
        if (tag != null && !tag.isBlank()) wrapper.like(Diary::getTags, "%" + tag + "%");
        if (start != null && !start.isBlank()) wrapper.ge(Diary::getDiaryDate, start);
        if (end != null && !end.isBlank()) wrapper.le(Diary::getDiaryDate, end);

        List<Diary> list = diaryMapper.selectList(wrapper);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Diary d : list) items.add(toListItem(d, userId, userService));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("total", items.size());
        resp.put("list", items);
        return resp;
    }

    /**
     * 软删除日记（移到回收站）。
     * PUT /api/diary/{id}/trash
     */
    @PutMapping("/{id}/trash")
    public Map<String, Object> trashDiary(@PathVariable Long id) {
        Diary d = diaryMapper.selectById(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (d == null) { resp.put("ok", false); resp.put("msg", "日记不存在"); return resp; }
        Long uid = UserContext.getUserId();
        if (d.getUserId() == null || !d.getUserId().equals(uid)) {
            resp.put("ok", false); resp.put("msg", "无权操作"); return resp;
        }
        d.setDeleted(1);
        diaryMapper.updateById(d);
        resp.put("ok", true); return resp;
    }

    /**
     * 日记回收站列表。
     * GET /api/diary/trash
     */
    @GetMapping("/trash")
    public Map<String, Object> diaryTrashList() {
        Long userId = UserContext.getUserId();
        List<Diary> list = diaryMapper.selectList(
                new LambdaQueryWrapper<Diary>()
                        .eq(Diary::getUserId, userId)
                        .eq(Diary::getDeleted, 1)
                        .orderByDesc(Diary::getUpdateTime)
                        .last("LIMIT 500")
        );
        List<Map<String, Object>> items = new ArrayList<>();
        for (Diary d : list) items.add(toListItem(d, userId, userService));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true); resp.put("total", items.size()); resp.put("list", items);
        return resp;
    }

    /**
     * 恢复日记。
     * PUT /api/diary/{id}/restore
     */
    @PutMapping("/{id}/restore")
    public Map<String, Object> restoreDiary(@PathVariable Long id) {
        Diary d = diaryMapper.selectById(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (d == null) { resp.put("ok", false); resp.put("msg", "日记不存在"); return resp; }
        Long uid = UserContext.getUserId();
        if (d.getUserId() == null || !d.getUserId().equals(uid)) {
            resp.put("ok", false); resp.put("msg", "无权操作"); return resp;
        }
        d.setDeleted(0);
        diaryMapper.updateById(d);
        resp.put("ok", true); return resp;
    }
}
