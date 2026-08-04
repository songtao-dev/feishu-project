package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.entity.MessageRecord;
import com.code.feishu.mapper.MessageRecordMapper;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计分析接口。
 *
 *   GET /api/stats/summary   汇总（总支出/收入/净额/笔数/日均）
 *   GET /api/stats/daily     每日趋势（折线图数据）
 *   GET /api/stats/category  分类占比（饼图数据）
 *   GET /api/stats/compare   对比（本期 vs 上期）
 *
 * 参数：
 *   range = week | month | custom
 *   start = 自定义开始日期（YYYY-MM-DD，range=custom 时生效）
 *   end   = 自定义结束日期
 */
@RestController
@RequestMapping("/api/stats")
public class StatisticsController {

    private final MessageRecordMapper recordMapper;

    public StatisticsController(MessageRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    // ==================== 汇总 ====================

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate[] dates = parseRange(range, start, end);
        List<MessageRecord> list = queryByDateRange(dates[0], dates[1]);

        BigDecimal totalExpense = sumAmount(list, "支出");
        BigDecimal totalIncome = sumAmount(list, "收入");
        int expenseCount = (int) list.stream().filter(r -> "支出".equals(r.getDirection())).count();
        long days = dates[0].until(dates[1]).getDays() + 1;
        BigDecimal avgDaily = days > 0 ? totalExpense.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalExpense", totalExpense);
        result.put("totalIncome", totalIncome);
        result.put("netAmount", totalIncome.subtract(totalExpense));
        result.put("expenseCount", expenseCount);
        result.put("incomeCount", (int) list.stream().filter(r -> "收入".equals(r.getDirection())).count());
        result.put("avgDaily", avgDaily);
        result.put("days", days);
        return result;
    }

    // ==================== 每日趋势 ====================

    @GetMapping("/daily")
    public Map<String, Object> daily(
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate[] dates = parseRange(range, start, end);
        List<MessageRecord> list = queryByDateRange(dates[0], dates[1]);

        // 按日期分组统计
        Map<String, BigDecimal> dailyExpense = new TreeMap<>();
        Map<String, BigDecimal> dailyIncome = new TreeMap<>();

        // 填充所有日期（含无消费的日期）
        LocalDate cur = dates[0];
        while (!cur.isAfter(dates[1])) {
            String dateStr = cur.toString();
            dailyExpense.put(dateStr, BigDecimal.ZERO);
            dailyIncome.put(dateStr, BigDecimal.ZERO);
            cur = cur.plusDays(1);
        }

        for (MessageRecord r : list) {
            if (r.getHappenTime() == null || r.getAmount() == null) continue;
            String dateStr = r.getHappenTime().length() >= 10 ? r.getHappenTime().substring(0, 10) : null;
            if (dateStr == null) continue;
            if ("支出".equals(r.getDirection())) {
                dailyExpense.merge(dateStr, r.getAmount(), BigDecimal::add);
            } else if ("收入".equals(r.getDirection())) {
                dailyIncome.merge(dateStr, r.getAmount(), BigDecimal::add);
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (String date : dailyExpense.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date);
            item.put("expense", dailyExpense.get(date));
            item.put("income", dailyIncome.get(date));
            series.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("series", series);
        return result;
    }

    // ==================== 分类占比 ====================

    @GetMapping("/category")
    public Map<String, Object> category(
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "merchant") String groupBy) {

        LocalDate[] dates = parseRange(range, start, end);
        List<MessageRecord> list = queryByDateRange(dates[0], dates[1]);

        // 按指定字段分组（merchant 或 channel）
        Map<String, BigDecimal> categoryMap = list.stream()
                .filter(r -> "支出".equals(r.getDirection()))
                .filter(r -> r.getAmount() != null)
                .collect(Collectors.groupingBy(
                        r -> {
                            if ("channel".equals(groupBy)) {
                                return r.getChannel() != null ? r.getChannel() : "其他";
                            }
                            return r.getMerchant() != null ? r.getMerchant() : "未知";
                        },
                        Collectors.reducing(BigDecimal.ZERO, MessageRecord::getAmount, BigDecimal::add)
                ));

        // 转为 [{name, value}] 并按金额降序
        List<Map<String, Object>> series = categoryMap.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("value", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("series", series);
        return result;
    }

    // ==================== 对比 ====================

    @GetMapping("/compare")
    public Map<String, Object> compare(
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate[] dates = parseRange(range, start, end);
        LocalDate[] prevDates = parsePrevRange(dates[0], dates[1], range);

        List<MessageRecord> currentList = queryByDateRange(dates[0], dates[1]);
        List<MessageRecord> prevList = queryByDateRange(prevDates[0], prevDates[1]);

        BigDecimal currentExpense = sumAmount(currentList, "支出");
        BigDecimal currentIncome = sumAmount(currentList, "收入");
        BigDecimal prevExpense = sumAmount(prevList, "支出");
        BigDecimal prevIncome = sumAmount(prevList, "收入");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", buildCompareItem(currentExpense, currentIncome, currentList.size()));
        result.put("previous", buildCompareItem(prevExpense, prevIncome, prevList.size()));

        // 变化百分比
        result.put("expenseChange", calcChange(currentExpense, prevExpense));
        result.put("incomeChange", calcChange(currentIncome, prevIncome));
        result.put("countChange", calcChange(BigDecimal.valueOf(currentList.size()), BigDecimal.valueOf(prevList.size())));

        result.put("currentLabel", dates[0].toString() + " ~ " + dates[1].toString());
        result.put("previousLabel", prevDates[0].toString() + " ~ " + prevDates[1].toString());
        return result;
    }

    // ==================== 工具方法 ====================

    /** 解析时间范围 */
    private LocalDate[] parseRange(String range, String start, String end) {
        LocalDate today = LocalDate.now();
        if ("custom".equals(range) && start != null && end != null) {
            return new LocalDate[]{LocalDate.parse(start), LocalDate.parse(end)};
        } else if ("week".equals(range)) {
            return new LocalDate[]{today.minusDays(6), today};
        } else {
            // month: 当月1号到今天
            return new LocalDate[]{today.withDayOfMonth(1), today};
        }
    }

    /** 计算上一个对比周期 */
    private LocalDate[] parsePrevRange(LocalDate start, LocalDate end, String range) {
        if ("week".equals(range)) {
            // 上周 = 本周开始前7天
            LocalDate prevEnd = start.minusDays(1);
            LocalDate prevStart = prevEnd.minusDays(6);
            return new LocalDate[]{prevStart, prevEnd};
        } else {
            // 上月
            YearMonth prevMonth = YearMonth.from(start).minusMonths(1);
            LocalDate prevStart = prevMonth.atDay(1);
            LocalDate prevEnd = prevMonth.atEndOfMonth();
            return new LocalDate[]{prevStart, prevEnd};
        }
    }

    /** 按日期范围查询记录 */
    private List<MessageRecord> queryByDateRange(LocalDate start, LocalDate end) {
        String startStr = start.toString() + " 00:00:00";
        String endStr = end.toString() + " 23:59:59";
        return recordMapper.selectList(
                new LambdaQueryWrapper<MessageRecord>()
                        .ge(MessageRecord::getHappenTime, startStr)
                        .le(MessageRecord::getHappenTime, endStr)
        );
    }

    /** 计算指定方向的金额总和 */
    private BigDecimal sumAmount(List<MessageRecord> list, String direction) {
        return list.stream()
                .filter(r -> direction.equals(r.getDirection()))
                .map(MessageRecord::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 构建对比项 */
    private Map<String, Object> buildCompareItem(BigDecimal expense, BigDecimal income, int count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("expense", expense);
        item.put("income", income);
        item.put("count", count);
        return item;
    }

    /** 计算变化百分比 */
    private String calcChange(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) > 0 ? "+100%" : "0%";
        }
        BigDecimal change = current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        String sign = change.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        return sign + change.setScale(1, RoundingMode.HALF_UP) + "%";
    }
}
