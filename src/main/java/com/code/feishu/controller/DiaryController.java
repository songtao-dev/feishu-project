package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.dto.DiaryDTO;
import com.code.feishu.entity.Diary;
import com.code.feishu.mapper.DiaryMapper;
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

    private final DiaryMapper diaryMapper;

    public DiaryController(DiaryMapper diaryMapper) {
        this.diaryMapper = diaryMapper;
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

        Diary diary = new Diary();
        diary.setUserId(userId);
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
        // 权限校验：只能查自己的日记
        Long userId = UserContext.getUserId();
        if (diary.getUserId() == null || !diary.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "无权查看此日记");
            return resp;
        }

        resp.put("ok", true);
        resp.put("diary", buildDiaryDetail(diary));
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
        // 权限校验：只能改自己的日记
        Long userId = UserContext.getUserId();
        if (diary.getUserId() == null || !diary.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "无权修改此日记");
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
        // 权限校验：只能删自己的日记
        Long userId = UserContext.getUserId();
        if (diary.getUserId() == null || !diary.getUserId().equals(userId)) {
            resp.put("ok", false);
            resp.put("msg", "无权删除此日记");
            return resp;
        }

        diaryMapper.deleteById(id);
        resp.put("ok", true);
        resp.put("msg", "删除成功");
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

    /** 解析日期字符串，失败返回默认值 */
    private LocalDate parseDate(String dateStr, LocalDate defaultValue) {
        if (dateStr == null || dateStr.isBlank()) return defaultValue;
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 构建日记详情返回对象（含完整字段） */
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
        item.put("diaryDate", d.getDiaryDate() != null ? d.getDiaryDate().toString() : null);
        item.put("createTime", d.getCreateTime());
        item.put("updateTime", d.getUpdateTime());
        return item;
    }

    /** 构建日记预览返回对象（列表用，正文只取前15字） */
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
}
