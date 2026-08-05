package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.entity.Diary;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.DiaryMapper;
import com.code.feishu.mapper.MessageRecordMapper;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 日迹接口：查询指定日期的日记 + 消费记录，形成"今日回眸"视图。
 *
 * GET /api/diary/daily?date=2025-08-05
 *   - date 可选，不传默认今天
 *   - 返回：日记详情 + 当日消费记录 + 收支汇总 + 趣味总结语
 */
@RestController
@RequestMapping("/api/diary")
public class DailyController {

    private static final Map<String, String> MOOD_EMOJI = new LinkedHashMap<>();
    private static final Map<String, String> MOOD_NAME = new LinkedHashMap<>();
    private static final Map<String, String> WEATHER_EMOJI = new LinkedHashMap<>();
    static {
        MOOD_EMOJI.put("very_happy", "😄"); MOOD_EMOJI.put("happy", "🙂");
        MOOD_EMOJI.put("ok", "😐"); MOOD_EMOJI.put("emo", "😔");
        MOOD_EMOJI.put("bad", "☹️"); MOOD_EMOJI.put("very_bad", "😢");

        MOOD_NAME.put("very_happy", "非常开心"); MOOD_NAME.put("happy", "开心");
        MOOD_NAME.put("ok", "不错"); MOOD_NAME.put("emo", "有点emo");
        MOOD_NAME.put("bad", "有点糟糕"); MOOD_NAME.put("very_bad", "很糟糕");

        WEATHER_EMOJI.put("sunny", "☀️"); WEATHER_EMOJI.put("cloudy", "☁️");
        WEATHER_EMOJI.put("rainy", "🌧️"); WEATHER_EMOJI.put("snowy", "❄️");
        WEATHER_EMOJI.put("windy", "💨"); WEATHER_EMOJI.put("foggy", "🌫️");
    }

    private final DiaryMapper diaryMapper;
    private final MessageRecordMapper recordMapper;

    public DailyController(DiaryMapper diaryMapper, MessageRecordMapper recordMapper) {
        this.diaryMapper = diaryMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 查询指定日期的日迹。
     *
     * 返回结构：
     * {
     *   "ok": true,
     *   "date": "2025-08-05",
     *   "weekday": "周二",
     *   "diary": { ... },          // 日记详情（可能为 null）
     *   "records": [ ... ],        // 当天消费记录列表
     *   "totalExpense": 38.50,     // 总支出
     *   "totalIncome": 0.00,       // 总收入
     *   "netAmount": -38.50,       // 净值（支出为负）
     *   "summary": "今日消费 ¥38.50，心情 🙂 开心，花钱买开心 ✨"
     * }
     */
    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(required = false) String date) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();

        // 解析日期，默认今天
        LocalDate queryDate;
        if (date == null || date.isBlank()) {
            queryDate = LocalDate.now();
        } else {
            try {
                queryDate = LocalDate.parse(date.trim());
            } catch (Exception e) {
                resp.put("ok", false);
                resp.put("msg", "日期格式错误，应为 YYYY-MM-DD");
                return resp;
            }
        }

        // ===== 1. 查询日记 =====
        Diary diary = diaryMapper.selectOne(
                new LambdaQueryWrapper<Diary>()
                        .eq(Diary::getUserId, userId)
                        .eq(Diary::getDiaryDate, queryDate)
                        .last("LIMIT 1")
        );

        // ===== 2. 查询当天消费记录 =====
        LocalDateTime dayStart = queryDate.atStartOfDay();
        LocalDateTime dayEnd = queryDate.atTime(LocalTime.MAX);

        List<MessageRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<MessageRecord>()
                        .eq(MessageRecord::getUserId, userId)
                        .ge(MessageRecord::getCreateTime, dayStart)
                        .le(MessageRecord::getCreateTime, dayEnd)
                        .orderByDesc(MessageRecord::getCreateTime)
        );

        // ===== 3. 计算汇总 =====
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        for (MessageRecord r : records) {
            if (r.getAmount() == null) continue;
            String dir = r.getDirection();
            if ("支出".equals(dir)) {
                totalExpense = totalExpense.add(r.getAmount());
            } else if ("收入".equals(dir)) {
                totalIncome = totalIncome.add(r.getAmount());
            }
        }
        BigDecimal netAmount = totalIncome.subtract(totalExpense);

        // ===== 4. 构建日记详情 =====
        Map<String, Object> diaryDetail = null;
        if (diary != null) {
            diaryDetail = buildDiaryDetail(diary);
        }

        // ===== 5. 构建记录列表 =====
        List<Map<String, Object>> recordList = new ArrayList<>();
        for (MessageRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("merchant", r.getMerchant());
            item.put("amount", r.getAmount());
            item.put("direction", r.getDirection());
            item.put("channel", r.getChannel());
            item.put("bank", r.getBank());
            item.put("cardTail", r.getCardTail());
            item.put("happenTime", r.getHappenTime());
            item.put("balance", r.getBalance());
            recordList.add(item);
        }

        // ===== 6. 生成趣味总结 =====
        String summary = buildSummary(queryDate, totalExpense, totalIncome, netAmount, diary, records);

        resp.put("ok", true);
        resp.put("date", queryDate.toString());
        resp.put("weekday", getWeekday(queryDate));
        resp.put("diary", diaryDetail);
        resp.put("records", recordList);
        resp.put("totalExpense", totalExpense);
        resp.put("totalIncome", totalIncome);
        resp.put("netAmount", netAmount);
        resp.put("recordCount", recordList.size());
        resp.put("summary", summary);
        resp.put("hasDiary", diary != null);
        resp.put("hasRecords", !recordList.isEmpty());
        return resp;
    }

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
        return item;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return Collections.emptyList();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String getWeekday(LocalDate date) {
        String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        return weekdays[date.getDayOfWeek().getValue() % 7];
    }

    /**
     * 根据消费和心情生成趣味总结语。
     */
    private String buildSummary(LocalDate date, BigDecimal expense, BigDecimal income,
                                BigDecimal net, Diary diary, List<MessageRecord> records) {
        StringBuilder sb = new StringBuilder();
        String dateStr = date.getMonthValue() + "月" + date.getDayOfMonth() + "日";

        // 消费部分
        if (expense.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("今日支出 ¥").append(expense.stripTrailingZeros().toPlainString());
        } else if (income.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("今日收入 ¥").append(income.stripTrailingZeros().toPlainString());
        } else {
            sb.append("今日无消费");
        }

        // 心情部分
        if (diary != null && diary.getMood() != null) {
            String emoji = MOOD_EMOJI.getOrDefault(diary.getMood(), "");
            String moodName = MOOD_NAME.getOrDefault(diary.getMood(), "");
            sb.append("，心情 ").append(emoji).append(" ").append(moodName);
        }

        // 趣味评语
        if (expense.compareTo(new BigDecimal("100")) >= 0 && diary != null
                && ("happy".equals(diary.getMood()) || "very_happy".equals(diary.getMood()))) {
            sb.append("，花钱买开心 ✨");
        } else if (expense.compareTo(new BigDecimal("100")) >= 0 && diary != null
                && ("bad".equals(diary.getMood()) || "very_bad".equals(diary.getMood()) || "emo".equals(diary.getMood()))) {
            sb.append("，花钱买罪受 😮‍💨");
        } else if (expense.compareTo(BigDecimal.ZERO) > 0 && diary == null) {
            sb.append("，赚了又花，白忙活 💸");
        } else if (expense.compareTo(BigDecimal.ZERO) <= 0 && diary != null) {
            sb.append("，没花钱也能开心 😌");
        } else if (income.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("，进账啦 🎉");
        } else {
            sb.append("，平平淡淡 🫰");
        }

        return sb.toString();
    }
}
